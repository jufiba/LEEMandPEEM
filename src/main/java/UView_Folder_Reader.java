/**
 * UView_Folder_Reader — opens a folder of UKSOFT2000/UView .dat files as an ImageJ stack.
 *
 * Bypasses SCIFIO entirely for maximum speed. Each file is read with a single I/O call;
 * the vertical flip is done in memory with System.arraycopy. LEEM metadata from each
 * file is stored as the slice label.
 *
 * Appears in Fiji as Plugins > UView Folder Reader.
 *
 * @author Juan de la Figuera
 */

import ij.*;
import ij.plugin.PlugIn;
import ij.process.*;
import ij.gui.*;
import ij.io.*;

import java.io.*;
import java.nio.*;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.*;
import java.util.*;

public class UView_Folder_Reader implements PlugIn {

	private static final String MAGIC        = "UKSOFT2001";
	private static final String[] UNIT_NAMES = {"", "V", "mA", "A", "\u00b0C", "K", "mV", "pA", "nA", "\u00b5A"};

	private static final String PREF_FILTER    = "LEEMandPEEM.folderReader.filter";
	private static final String PREF_START     = "LEEMandPEEM.folderReader.startImg";
	private static final String PREF_NUM       = "LEEMandPEEM.folderReader.numImages";
	private static final String PREF_INCREMENT = "LEEMandPEEM.folderReader.increment";

	@Override
	public void run(String arg) {
		DirectoryChooser dc = new DirectoryChooser("Open folder with UView .dat files");
		String dir = dc.getDirectory();
		if (dir == null) return;

		File folder = new File(dir);
		File[] allFiles = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".dat"));
		if (allFiles == null || allFiles.length == 0) {
			IJ.error("UView Folder Reader", "No .dat files found in:\n" + dir);
			return;
		}

		// --- options dialog (restore last-used values) ---
		String prevFilter    = ij.Prefs.get   (PREF_FILTER,    "");
		int    prevStart     = (int) ij.Prefs.get(PREF_START,     1);
		int    prevNum       = (int) ij.Prefs.get(PREF_NUM,       allFiles.length);
		int    prevIncrement = (int) ij.Prefs.get(PREF_INCREMENT, 1);

		GenericDialog gd = new GenericDialog("UView Folder Reader");
		gd.addStringField("File name contains:",  prevFilter,    20);
		gd.addNumericField("Starting image:",       prevStart,     0);
		gd.addNumericField("Number of images:",     prevNum,       0);
		gd.addNumericField("Increment:",            prevIncrement, 0);
		// Not persisted on purpose: a remembered single-image choice would
		// silently drop frames from multi-image files on the next run.
		gd.addNumericField("Image within .dat (0 = all):", 0, 0);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		String filter    =        gd.getNextString().trim();
		int    startImg  = Math.max(1, (int) gd.getNextNumber());
		int    numImages = Math.max(1, (int) gd.getNextNumber());
		int    increment = Math.max(1, (int) gd.getNextNumber());
		int    imageInDat = Math.max(0, (int) gd.getNextNumber());   // 0 = all

		ij.Prefs.set(PREF_FILTER,    filter);
		ij.Prefs.set(PREF_START,     startImg);
		ij.Prefs.set(PREF_NUM,       numImages);
		ij.Prefs.set(PREF_INCREMENT, increment);

		ImagePlus imp;
		try {
			imp = openFolder(folder, filter, startImg, numImages, increment, imageInDat);
		} catch (ReadException e) {
			IJ.error("UView Folder Reader", e.getMessage());
			return;
		}
		imp.show();
	}

	/** Reason a folder could not be opened; carries a user-readable message. */
	static class ReadException extends Exception {
		ReadException(String msg) { super(msg); }
	}

	/**
	 * Reads a folder of .dat files into a stack without any dialog, so other
	 * plugins (e.g. Multiple_UView_Folder_Reading) can batch it. The returned
	 * image is not shown.
	 *
	 * @param numImages  number of files to take; {@code <= 0} means all
	 * @param imageInDat 1-based image within each .dat, or 0 for all
	 * @throws ReadException if nothing could be read; never pops a dialog
	 */
	static ImagePlus openFolder(File folder, String filter, int startImg, int numImages,
	                            int increment, int imageInDat) throws ReadException {
		File[] allFiles = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".dat"));
		if (allFiles == null || allFiles.length == 0)
			throw new ReadException("No .dat files found in:\n" + folder.getAbsolutePath());
		Arrays.sort(allFiles);
		if (numImages <= 0) numImages = allFiles.length;
		startImg  = Math.max(1, startImg);
		increment = Math.max(1, increment);

		// --- load CSV tags (Energy, M4b) from *_meta.csv or a legacy CSV ---
		Map<String, Map<String, String>> csvTags = loadCsvTags(folder, allFiles);

		// apply filename filter
		List<File> filtered = new ArrayList<>();
		for (File f : allFiles)
			if (filter.isEmpty() || f.getName().contains(filter))
				filtered.add(f);

		if (filtered.isEmpty())
			throw new ReadException("No files match the filter \"" + filter + "\".");

		// apply range: starting image (1-based), count, increment
		int from = startImg - 1;                          // 0-based
		int to   = Math.min(from + numImages * increment, filtered.size());
		List<File> selected = new ArrayList<>();
		for (int i = from; i < to; i += increment)
			selected.add(filtered.get(i));

		if (selected.isEmpty())
			throw new ReadException("No files in the specified range.");

		// --- read selected files ---
		ImageStack stack = null;
		int width = 0, height = 0;
		int skipped = 0;

		IJ.showStatus("Reading " + selected.size() + " UView files...");

		for (int n = 0; n < selected.size(); n++) {
			IJ.showProgress(n, selected.size());
			File f = selected.get(n);
			try {
			  // A .dat may contain several images; each becomes one slice.
			  // `skipped` counts files, not frames: a size mismatch rejects the
			  // whole file, since the stack's dimensions are already fixed.
			  List<FrameData> frames = readDat(f, imageInDat);
			  if (frames.isEmpty()) {
				IJ.log("Skipped (has fewer than " + imageInDat + " images): " + f.getName());
				skipped++;
				continue;
			  }
			  if (stack != null && !frames.isEmpty()
					  && (frames.get(0).width != width || frames.get(0).height != height)) {
				IJ.log("Skipped (different size): " + f.getName());
				skipped++;
				continue;
			  }
			  for (FrameData frame : frames) {
				if (stack == null) {
					width  = frame.width;
					height = frame.height;
					stack  = new ImageStack(width, height);
				}
				// append CSV tags (Energy, M4b) to slice label if available
				Map<String, String> extra = csvTags.get(f.getName());
				if (extra != null && !extra.isEmpty()) {
					StringBuilder sb = new StringBuilder(frame.label);
					for (Map.Entry<String, String> e : extra.entrySet())
						sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
					frame.label = sb.toString();
				}
				String title = frame.total > 1
						? f.getName() + " [" + frame.index + "/" + frame.total + "]"
						: f.getName();
				ShortProcessor sp = new ShortProcessor(width, height, frame.pixels, null);
				stack.addSlice(title + "\n" + frame.label, sp);
			  }
			} catch (Exception e) {
				IJ.log("Skipped (read error): " + f.getName() + " — " + e.getMessage());
				skipped++;
			}
		}

		IJ.showProgress(1.0);
		IJ.showStatus("");

		if (skipped > 0)
			IJ.log("UView Folder Reader: skipped " + skipped + " file(s) in " + folder.getName() + ".");

		if (stack == null || stack.size() == 0)
			throw new ReadException("No valid .dat files could be read in:\n"
					+ folder.getAbsolutePath());

		return new ImagePlus(folder.getName(), stack);
	}

	// -------------------------------------------------------------------------

	/**
	 * Finds the CSV holding the per-image energies and parses it.
	 *
	 * Search order (first file that has an Energy or M4b column wins):
	 * <ol>
	 *   <li>{@code *_meta.csv} in the data folder (e.g.
	 *       {@code Mn_Fe_2_Mo3O8-014-XAS_meta.csv}); one whose prefix matches
	 *       the folder name is preferred if there are several,</li>
	 *   <li>{@code <folder name>_meta.csv} next to the data folder,</li>
	 *   <li>the legacy layout: any other {@code .csv} in the data folder.</li>
	 * </ol>
	 *
	 * Returns a map from .dat filename to tag key → value. If the CSV has a
	 * column whose values are the .dat filenames, rows are matched by name;
	 * otherwise row n is assigned to the n-th .dat file in sorted order.
	 */
	private static Map<String, Map<String, String>> loadCsvTags(File folder, File[] sortedFiles) {
		List<File> candidates = new ArrayList<>();

		File[] metaHere = folder.listFiles((d, name) -> name.toLowerCase().endsWith("_meta.csv"));
		if (metaHere != null && metaHere.length > 0) {
			Arrays.sort(metaHere);
			String folderName = folder.getName().toLowerCase();
			for (File f : metaHere)                       // name matches folder first
				if (f.getName().toLowerCase().equals(folderName + "_meta.csv")) candidates.add(f);
			for (File f : metaHere)
				if (!candidates.contains(f)) candidates.add(f);
		}
		File parent = folder.getAbsoluteFile().getParentFile();
		if (parent != null) {
			File besideFolder = new File(parent, folder.getName() + "_meta.csv");
			if (besideFolder.isFile()) candidates.add(besideFolder);
		}
		File[] legacy = folder.listFiles((d, name) -> {
			String lower = name.toLowerCase();
			return lower.endsWith(".csv") && !lower.endsWith("_meta.csv");
		});
		if (legacy != null) {
			Arrays.sort(legacy);
			candidates.addAll(Arrays.asList(legacy));
		}

		if (candidates.isEmpty()) {
			IJ.log("UView Folder Reader: no CSV file found in " + folder.getAbsolutePath());
			return Collections.emptyMap();
		}
		for (File csv : candidates) {
			Map<String, Map<String, String>> r = parseCsv(csv, sortedFiles);
			if (r != null) return r;
		}
		IJ.log("UView Folder Reader: no CSV with an 'Energy' or 'M4b' column — energies not added.");
		return Collections.emptyMap();
	}

	/** Parses one CSV; returns null if it has neither an Energy nor an M4b column. */
	private static Map<String, Map<String, String>> parseCsv(File csvFile, File[] sortedFiles) {
		IJ.log("UView Folder Reader: reading CSV " + csvFile.getAbsolutePath());
		Map<String, Map<String, String>> result = new LinkedHashMap<>();
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
			String header = br.readLine();
			while (header != null && header.trim().isEmpty()) header = br.readLine();
			if (header == null) {
				IJ.log("UView Folder Reader: CSV is empty.");
				return null;
			}
			// strip UTF-8 BOM if present (Excel on Windows adds this)
			if (header.startsWith("\uFEFF")) header = header.substring(1);
			IJ.log("UView Folder Reader: CSV header = [" + header + "]");

			String delim = header.contains(";") ? ";" : header.contains("\t") ? "\t" : ",";
			String[] cols = header.split(delim, -1);
			for (int i = 0; i < cols.length; i++) cols[i] = unquote(cols[i]);

			int energyIdx = findColumn(cols, "energy");
			int m4bIdx    = findColumn(cols, "m4b");
			IJ.log("UView Folder Reader: energy column = "
					+ (energyIdx >= 0 ? "[" + cols[energyIdx] + "]" : "none")
					+ ", M4b column = " + (m4bIdx >= 0 ? "[" + cols[m4bIdx] + "]" : "none"));
			if (energyIdx < 0 && m4bIdx < 0) return null;

			Set<String> datNames = new HashSet<>();
			for (File f : sortedFiles) datNames.add(f.getName());

			List<String[]> rows = new ArrayList<>();
			String line;
			while ((line = br.readLine()) != null) {
				if (line.trim().isEmpty()) continue;
				String[] vals = line.split(delim, -1);
				for (int i = 0; i < vals.length; i++) vals[i] = unquote(vals[i]);
				rows.add(vals);
			}

			// A column holding the .dat filenames lets rows be matched by name,
			// which survives missing/extra rows. Otherwise fall back to row order.
			int nameIdx = -1;
			if (!rows.isEmpty()) {
				String[] first = rows.get(0);
				for (int c = 0; c < first.length && nameIdx < 0; c++)
					if (datNames.contains(baseName(first[c]))) nameIdx = c;
			}
			if (nameIdx >= 0)
				IJ.log("UView Folder Reader: matching rows to .dat files by column ["
						+ (nameIdx < cols.length ? cols[nameIdx] : "#" + nameIdx) + "]");
			else if (rows.size() != sortedFiles.length)
				IJ.log("UView Folder Reader: warning — CSV has " + rows.size()
						+ " rows but the folder has " + sortedFiles.length
						+ " .dat files; matching by row order.");

			for (int r = 0; r < rows.size(); r++) {
				String[] vals = rows.get(r);
				String datName;
				if (nameIdx >= 0) {
					if (nameIdx >= vals.length) continue;
					datName = baseName(vals[nameIdx]);
					if (!datNames.contains(datName)) continue;
				} else {
					if (r >= sortedFiles.length) break;
					datName = sortedFiles[r].getName();
				}
				Map<String, String> tags = new LinkedHashMap<>();
				if (energyIdx >= 0 && energyIdx < vals.length && !vals[energyIdx].isEmpty())
					tags.put("Energy (eV)", numeric(vals[energyIdx]));
				if (m4bIdx >= 0 && m4bIdx < vals.length && !vals[m4bIdx].isEmpty())
					tags.put("M4b", numeric(vals[m4bIdx]));
				if (!tags.isEmpty()) result.put(datName, tags);
			}
			IJ.log("UView Folder Reader: loaded " + result.size() + " rows from "
					+ csvFile.getName() + ".");
		} catch (IOException e) {
			IJ.log("UView Folder Reader: could not read CSV " + csvFile.getName()
					+ " — " + e.getMessage());
			return null;
		}
		return result;
	}

	/**
	 * Column index for {@code key}: an exact (case-insensitive) match wins,
	 * then a header that starts with it (e.g. "Energy (eV)", "Energy_eV"),
	 * then one that contains it. -1 if none.
	 */
	private static int findColumn(String[] cols, String key) {
		for (int i = 0; i < cols.length; i++)
			if (cols[i].equalsIgnoreCase(key)) return i;
		for (int i = 0; i < cols.length; i++)
			if (cols[i].toLowerCase().startsWith(key)) return i;
		for (int i = 0; i < cols.length; i++)
			if (cols[i].toLowerCase().contains(key)) return i;
		return -1;
	}

	private static String unquote(String s) {
		s = s.trim();
		if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\""))
			s = s.substring(1, s.length() - 1).trim();
		return s;
	}

	/** Strips any directory part, so "C:\\data\\img001.dat" matches "img001.dat". */
	private static String baseName(String s) {
		int k = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
		return k >= 0 ? s.substring(k + 1) : s;
	}

	/**
	 * plotIntensityVsTag parses tag values with Double.parseDouble, so a
	 * decimal comma ("710,5", common in ';'-separated CSVs) must become a dot.
	 */
	private static String numeric(String v) {
		if (v.indexOf(',') >= 0 && v.indexOf('.') < 0) {
			String dotted = v.replace(',', '.');
			try { Double.parseDouble(dotted); return dotted; } catch (NumberFormatException ignored) { }
		}
		return v;
	}

	// -------------------------------------------------------------------------

	private static class FrameData {
		int     width, height;
		short[] pixels;
		String  label;
		int     index, total;   // 1-based position within its .dat file
	}

	/**
	 * Reads every image in a .dat file. A .dat may hold NrImages images, each
	 * with its own image header, markup block, LEEM data block and pixel data
	 * (see the format spec, "Data File containing multiple images"). The walk
	 * is checked against the file length so a layout we misunderstand fails
	 * loudly instead of silently returning the wrong frame.
	 *
	 * @param only 1-based image to return, or 0 for all. The chain is still
	 *             walked in full so the length check applies; only the
	 *             selected image's metadata and pixels are read. Returns an
	 *             empty list if the file holds fewer than {@code only} images.
	 */
	private static List<FrameData> readDat(File file, int only) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file, "r")) {

			// --- verify magic ---
			byte[] magic = new byte[MAGIC.length()];
			f.readFully(magic);
			if (!new String(magic, StandardCharsets.ISO_8859_1).startsWith(MAGIC))
				throw new IOException("Not a UView file");

			// --- file header ---
			f.seek(20);
			int UKFH_size    = readUShort(f);
			int UKFH_version = readUShort(f);
			// bitsperpixel at 24 — not needed

			f.seek(40);
			int width     = readUShort(f);
			int height    = readUShort(f);
			int nrImages  = Math.max(1, readUShort(f));   // offset 44

			int recipeBlockSize = 0;
			if (UKFH_version >= 7) {
				f.seek(46);
				recipeBlockSize = readUShort(f) > 0 ? 128 : 0;
			}

			final int frameBytes = width * height * 2;
			final List<FrameData> frames = new ArrayList<>(nrImages);
			long p = UKFH_size + recipeBlockSize;

			for (int n = 0; n < nrImages; n++) {
				// --- image header ---
				f.seek(p);
				int  UKIH_size    = readUShort(f);
				int  UKIH_version = readUShort(f);
				/*colorlow*/        readUShort(f);
				/*colorhigh*/       readUShort(f);
				long UKIH_time    = readLong(f);     // offset 8
				/*maskx*/           readUShort(f);   // offset 16
				/*masky*/           readUShort(f);   // offset 18
				/*rotateMask*/      readUShort(f);   // offset 20
				int  attachedMarkupSize = readUShort(f); // offset 22
				/*spin*/            readUShort(f);   // offset 24
				int  leemdatasize = readUShort(f);   // offset 26

				int markupSize = attachedMarkupSize > 0
						? 128 * ((attachedMarkupSize / 128) + 1) : 0;
				int leemBlockSize = leemdatasize > 2 ? leemdatasize : 0;
				long dataOffset = p + UKIH_size + markupSize + leemBlockSize;

				if (dataOffset + frameBytes > f.length())
					throw new IOException("image " + (n + 1) + " of " + nrImages
							+ " runs past end of file (offset " + dataOffset + ")");

				if (only != 0 && n + 1 != only) {
					p = dataOffset + frameBytes;
					continue;
				}

				// --- LEEM data block -> slice label ---
				Map<String, String> meta = new LinkedHashMap<>();
				meta.put("Date", formatTime(UKIH_time));
				if (leemdatasize >= 1) {
					byte[] leemBlock;
					if (leemdatasize > 2) {
						f.seek(p + UKIH_size + markupSize);
						leemBlock = new byte[leemdatasize];
					} else {
						// LEEMdata array at image-header offset 28. Its size depends on
						// the IMAGE HEADER version, not on UKIH_size:
						//   version <= 5: LEEMdata[256], then a 4-byte spare
						//   version  > 5: LEEMdata[239], then applied_processing / gray
						//                 adjust zone / backgroundvalue / rendering fields
						f.seek(p + 28);
						leemBlock = new byte[Math.min(UKIH_version > 5 ? 239 : 256,
								UKIH_size - 28)];
					}
					f.readFully(leemBlock);
					parseLEEM(leemBlock, leemdatasize > 1, meta);
				}

				// --- pixel data ---
				f.seek(dataOffset);
				byte[] raw = new byte[frameBytes];
				f.readFully(raw);

				// vertical flip: swap rows using System.arraycopy, then bulk short conversion
				int rowBytes = width * 2;
				byte[] flipped = new byte[raw.length];
				for (int row = 0; row < height; row++)
					System.arraycopy(raw, (height - 1 - row) * rowBytes,
					                 flipped, row * rowBytes, rowBytes);

				short[] pixels = new short[width * height];
				ByteBuffer.wrap(flipped).order(ByteOrder.LITTLE_ENDIAN)
				          .asShortBuffer().get(pixels);

				StringBuilder sb = new StringBuilder();
				for (Map.Entry<String, String> e : meta.entrySet())
					sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');

				FrameData fd = new FrameData();
				fd.width  = width;
				fd.height = height;
				fd.pixels = pixels;
				fd.label  = sb.toString();
				fd.index  = n + 1;
				fd.total  = nrImages;
				frames.add(fd);

				p = dataOffset + frameBytes;
			}

			if (p != f.length())
				IJ.log("Warning: " + file.getName() + " — walked " + nrImages
						+ " image(s) to offset " + p + " but file is " + f.length()
						+ " bytes; layout may be misread.");

			return frames;
		}
	}

	private static void parseLEEM(byte[] block, boolean readAveragingBytes,
	                               Map<String, String> meta) {
		int i = 0;
		while (i < block.length) {
			int rawTag = block[i++] & 0xFF;
			// Spec: 0xFF means SKIP this byte, it is NOT an end-of-block marker.
			// The block length is the terminator.
			if (rawTag == 0xFF) continue;
			int tag = rawTag & 0x7F; // strip "hidden" bit

			switch (tag) {
			case 100: {
				float x = getFloat(block, i); i += 4;
				float y = getFloat(block, i); i += 4;
				meta.put("MicrometerX", fmt(x));
				meta.put("MicrometerY", fmt(y));
				break;
			}
			case 101: {
				int end = indexOf0(block, i);
				meta.put("FOV", new String(block, i, end - i, StandardCharsets.ISO_8859_1));
				i = end + 1;
				break;
			}
			case 102:
				meta.put("Varian1", fmt(getFloat(block, i))); i += 4; break;
			case 103:
				meta.put("Varian2", fmt(getFloat(block, i))); i += 4; break;
			case 104: {
				meta.put("CameraExposure", fmt(getFloat(block, i)) + " s"); i += 4;
				if (readAveragingBytes) {
					// B1>0 averaging on, B2 = number of images (2..127);
					// B1==0 averaging off; B1<0 (0xFF) sliding average.
					int b1 = block[i] & 0xFF, b2 = block[i + 1] & 0xFF; i += 2;
					meta.put("Averaging", b1 == 0 ? "off"
							: b1 > 127 ? "sliding" : Integer.toString(b2));
				}
				break;
			}
			case 105: {
				int end = indexOf0(block, i);
				String title = new String(block, i, end - i, StandardCharsets.ISO_8859_1).trim();
				if (!title.isEmpty()) meta.put("Title", title);
				i = end + 1;
				break;
			}
			// Varian gauges #1-#4, plus the additional gauges #5.. at 120-130.
			// All share the same layout: name, units, float.
			case 106: case 107: case 108: case 109:
			case 120: case 121: case 122: case 123: case 124: case 125:
			case 126: case 127: case 128: case 129: case 130: {
				int end1 = indexOf0(block, i);
				String name = new String(block, i, end1 - i, StandardCharsets.ISO_8859_1); i = end1 + 1;
				int end2 = indexOf0(block, i);
				String units = new String(block, i, end2 - i, StandardCharsets.ISO_8859_1); i = end2 + 1;
				meta.put(name + " (" + units + ")", fmt(getFloat(block, i))); i += 4;
				break;
			}
			case 110: {
				// Spec: "FOV, camera to FOV cal. factor": the string is the FOV
				// name, the float is the calibration factor.
				int end = indexOf0(block, i);
				meta.put("FOVName", new String(block, i, end - i, StandardCharsets.ISO_8859_1)); i = end + 1;
				meta.put("FOVCalFactor", fmt(getFloat(block, i))); i += 4;
				break;
			}
			case 111:
				meta.put("Phi",   fmt(getFloat(block, i))); i += 4;
				meta.put("Theta", fmt(getFloat(block, i))); i += 4;
				break;
			case 112:
				// Spin. Payload size is not documented; 2 bytes determined
				// empirically (spec declares spin a short).
				meta.put("Spin",
						Integer.toString((short) ((block[i] & 0xFF) | (block[i + 1] << 8))));
				i += 2; break;
			case 113:
				// FOV rotation (from LEEM presets). Payload size is not documented;
				// 4 bytes determined empirically.
				meta.put("FOVRotation", fmt(getFloat(block, i))); i += 4; break;
			case 114:
				// Mirror state. Payload size is not documented; 2 bytes empirically.
				meta.put("MirrorState",
						Integer.toString((short) ((block[i] & 0xFF) | (block[i + 1] << 8))));
				i += 2; break;
			case 115:
				meta.put("MCPScreenVoltage",  fmt(getFloat(block, i)) + " kV"); i += 4; break;
			case 116:
				meta.put("MCPChannelPlate",   fmt(getFloat(block, i)) + " kV"); i += 4; break;
			default:
				if (tag < 100) {
					// LEEM2000 module reading: name + unit digit + NUL + float.
					int end = indexOf0(block, i);
					String nameAndUnit = new String(block, i, end - i, StandardCharsets.ISO_8859_1); i = end + 1;
					float val = getFloat(block, i); i += 4;
					if (nameAndUnit.length() > 0) {
						char   unitCode = nameAndUnit.charAt(nameAndUnit.length() - 1);
						String modName  = nameAndUnit.substring(0, nameAndUnit.length() - 1);
						String unit     = (unitCode >= '0' && unitCode <= '9')
								? UNIT_NAMES[unitCode - '0'] : "";
						String key = unit.isEmpty() ? modName : modName + " (" + unit + ")";
						meta.put(key, fmt(val));
					}
					break;
				}
				// Unknown tag >= 100: payload size unknown, so the byte stream can no
				// longer be interpreted. Stop rather than resynchronise on garbage
				// (e.g. tag 112, spin, whose size the format spec does not give).
				meta.put("UnreadLEEMTag", "tag " + tag + " at block offset " + (i - 1));
				i = block.length;
				break;
			}
		}
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private static int readUShort(RandomAccessFile f) throws IOException {
		return (f.read() & 0xFF) | ((f.read() & 0xFF) << 8);
	}

	private static long readLong(RandomAccessFile f) throws IOException {
		byte[] b = new byte[8];
		f.readFully(b);
		return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
	}

	private static float getFloat(byte[] buf, int offset) {
		return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
	}

	private static int indexOf0(byte[] buf, int from) {
		for (int i = from; i < buf.length; i++)
			if (buf[i] == 0) return i;
		return buf.length;
	}

	private static String fmt(float v) {
		// Locale.US: these values are parsed back with Double.parseDouble by
		// plotIntensityVsTag, which only accepts '.' as the decimal separator.
		// Under a comma-decimal locale every numeric tag was silently dropped
		// from the tag dropdown.
		return String.format(java.util.Locale.US, "%.4g", v);
	}

	private static String formatTime(long winFileTime) {
		long ms = (winFileTime - 116444736000000000L) / 10000L;
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
	}
}
