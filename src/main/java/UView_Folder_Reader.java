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
		Arrays.sort(allFiles);

		// --- load CSV tags (Energy, M4b) if a matching CSV exists ---
		Map<String, Map<String, String>> csvTags = loadCsvTags(folder, allFiles);

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
		gd.showDialog();
		if (gd.wasCanceled()) return;

		String filter    =        gd.getNextString().trim();
		int    startImg  = Math.max(1, (int) gd.getNextNumber());
		int    numImages = Math.max(1, (int) gd.getNextNumber());
		int    increment = Math.max(1, (int) gd.getNextNumber());

		ij.Prefs.set(PREF_FILTER,    filter);
		ij.Prefs.set(PREF_START,     startImg);
		ij.Prefs.set(PREF_NUM,       numImages);
		ij.Prefs.set(PREF_INCREMENT, increment);

		// apply filename filter
		List<File> filtered = new ArrayList<>();
		for (File f : allFiles)
			if (filter.isEmpty() || f.getName().contains(filter))
				filtered.add(f);

		if (filtered.isEmpty()) {
			IJ.error("UView Folder Reader", "No files match the filter \"" + filter + "\".");
			return;
		}

		// apply range: starting image (1-based), count, increment
		int from = startImg - 1;                          // 0-based
		int to   = Math.min(from + numImages * increment, filtered.size());
		List<File> selected = new ArrayList<>();
		for (int i = from; i < to; i += increment)
			selected.add(filtered.get(i));

		if (selected.isEmpty()) {
			IJ.error("UView Folder Reader", "No files in the specified range.");
			return;
		}

		// --- read selected files ---
		ImageStack stack = null;
		int width = 0, height = 0;
		int skipped = 0;

		IJ.showStatus("Reading " + selected.size() + " UView files...");

		for (int n = 0; n < selected.size(); n++) {
			IJ.showProgress(n, selected.size());
			File f = selected.get(n);
			try {
				FrameData frame = readDat(f);
				if (stack == null) {
					width  = frame.width;
					height = frame.height;
					stack  = new ImageStack(width, height);
				} else if (frame.width != width || frame.height != height) {
					IJ.log("Skipped (different size): " + f.getName());
					skipped++;
					continue;
				}
				// append CSV tags (Energy, M4b) to slice label if available
				Map<String, String> extra = csvTags.get(f.getName());
				if (extra != null && !extra.isEmpty()) {
					StringBuilder sb = new StringBuilder(frame.label);
					for (Map.Entry<String, String> e : extra.entrySet())
						sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
					frame.label = sb.toString();
				}
				ShortProcessor sp = new ShortProcessor(width, height, frame.pixels, null);
				stack.addSlice(f.getName() + "\n" + frame.label, sp);
			} catch (Exception e) {
				IJ.log("Skipped (read error): " + f.getName() + " — " + e.getMessage());
				skipped++;
			}
		}

		IJ.showProgress(1.0);
		IJ.showStatus("");

		if (stack == null || stack.size() == 0) {
			IJ.error("UView Folder Reader", "No valid .dat files could be read.");
			return;
		}

		ImagePlus imp = new ImagePlus(folder.getName(), stack);
		imp.show();

		if (skipped > 0)
			IJ.log("UView Folder Reader: skipped " + skipped + " file(s).");
	}

	// -------------------------------------------------------------------------

	/**
	 * Looks for a CSV file in {@code folder} whose name does not contain "meta".
	 * Parses its "Energy" and "M4b" columns and returns a map from each .dat
	 * filename (by sorted position) to a map of tag key → value.
	 */
	private static Map<String, Map<String, String>> loadCsvTags(File folder, File[] sortedFiles) {
		File[] csvFiles = folder.listFiles((d, name) -> {
			String lower = name.toLowerCase();
			return lower.endsWith(".csv") && !lower.contains("meta");
		});
		if (csvFiles == null || csvFiles.length == 0) {
			IJ.log("UView Folder Reader: no CSV file found in " + folder.getAbsolutePath());
			return Collections.emptyMap();
		}

		Arrays.sort(csvFiles);
		File csvFile = csvFiles[0];
		IJ.log("UView Folder Reader: found CSV " + csvFile.getAbsolutePath());

		Map<String, Map<String, String>> result = new LinkedHashMap<>();
		try (BufferedReader br = new BufferedReader(
				new InputStreamReader(new FileInputStream(csvFile), StandardCharsets.UTF_8))) {
			String header = br.readLine();
			if (header == null) {
				IJ.log("UView Folder Reader: CSV is empty.");
				return result;
			}
			// strip UTF-8 BOM if present (Excel on Windows adds this)
			if (header.startsWith("\uFEFF")) {
				IJ.log("UView Folder Reader: stripped UTF-8 BOM from CSV header.");
				header = header.substring(1);
			}
			IJ.log("UView Folder Reader: CSV header = [" + header + "]");

			String delim = header.contains(";") ? ";" : ",";
			IJ.log("UView Folder Reader: delimiter = [" + delim + "]");
			String[] cols = header.split(delim);
			int energyIdx = -1, m4bIdx = -1;
			for (int i = 0; i < cols.length; i++) {
				String col = cols[i].trim();
				IJ.log("  col[" + i + "] = [" + col + "] (len=" + col.length() + ")");
				if (col.equalsIgnoreCase("Energy")) energyIdx = i;
				else if (col.equalsIgnoreCase("M4b"))  m4bIdx  = i;
			}
			IJ.log("UView Folder Reader: energyIdx=" + energyIdx + " m4bIdx=" + m4bIdx);
			if (energyIdx < 0 && m4bIdx < 0) {
				IJ.log("UView Folder Reader: neither 'Energy' nor 'M4b' column found — skipping CSV.");
				return result;
			}

			int fileIdx = 0;
			String line;
			while ((line = br.readLine()) != null && fileIdx < sortedFiles.length) {
				String[] vals = line.split(delim);
				Map<String, String> tags = new LinkedHashMap<>();
				if (energyIdx >= 0 && energyIdx < vals.length)
					tags.put("Energy (eV)", vals[energyIdx].trim());
				if (m4bIdx >= 0 && m4bIdx < vals.length)
					tags.put("M4b", vals[m4bIdx].trim());
				if (!tags.isEmpty())
					result.put(sortedFiles[fileIdx].getName(), tags);
				fileIdx++;
			}
			IJ.log("UView Folder Reader: loaded " + result.size() + " rows from CSV.");
		} catch (IOException e) {
			IJ.log("UView Folder Reader: could not read CSV " + csvFile.getName()
					+ " — " + e.getMessage());
		}
		return result;
	}

	// -------------------------------------------------------------------------

	private static class FrameData {
		int     width, height;
		short[] pixels;
		String  label;
	}

	private FrameData readDat(File file) throws IOException {
		try (RandomAccessFile f = new RandomAccessFile(file, "r")) {

			// --- verify magic ---
			byte[] magic = new byte[MAGIC.length()];
			f.readFully(magic);
			if (!new String(magic).startsWith(MAGIC))
				throw new IOException("Not a UView file");

			// --- file header ---
			f.seek(20);
			int UKFH_size    = readUShort(f);
			int UKFH_version = readUShort(f);
			// bitsperpixel at 24 — not needed

			f.seek(40);
			int width  = readUShort(f);
			int height = readUShort(f);

			int recipeBlockSize = 0;
			if (UKFH_version >= 7) {
				f.seek(46);
				recipeBlockSize = readUShort(f) > 0 ? 128 : 0;
			}

			// --- image header ---
			long imgHdrStart = UKFH_size + recipeBlockSize;
			f.seek(imgHdrStart);
			int  UKIH_size    = readUShort(f);
			/*version*/         readUShort(f);
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

			// --- read image data in one shot ---
			long imageOffset = f.length() - 2L * width * height;
			f.seek(imageOffset);
			byte[] raw = new byte[width * height * 2];
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

			// --- parse LEEM data block for slice label ---
			Map<String, String> meta = new LinkedHashMap<>();
			meta.put("Date", formatTime(UKIH_time));
			if (leemdatasize > 2) {
				long leemOffset = imgHdrStart + UKIH_size + markupSize;
				f.seek(leemOffset);
				byte[] leemBlock = new byte[leemdatasize];
				f.readFully(leemBlock);
				parseLEEM(leemBlock, leemdatasize > 1, meta);
			}

			StringBuilder sb = new StringBuilder();
			for (Map.Entry<String, String> e : meta.entrySet())
				sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');

			FrameData fd = new FrameData();
			fd.width  = width;
			fd.height = height;
			fd.pixels = pixels;
			fd.label  = sb.toString();
			return fd;
		}
	}

	private static void parseLEEM(byte[] block, boolean readAveragingBytes,
	                               Map<String, String> meta) {
		int i = 0;
		while (i < block.length) {
			int rawTag = block[i++] & 0xFF;
			if (rawTag == 0xFF) break;
			int tag = rawTag & 0x7F; // strip "hidden" bit

			switch (tag) {
			case 16:
				i++;
				break;
			case 100: {
				float x = getFloat(block, i); i += 4;
				float y = getFloat(block, i); i += 4;
				meta.put("MicrometerX", fmt(x));
				meta.put("MicrometerY", fmt(y));
				break;
			}
			case 101: {
				int end = indexOf0(block, i);
				meta.put("FOV", new String(block, i, end - i));
				i = end + 1;
				break;
			}
			case 102:
				meta.put("Varian1", fmt(getFloat(block, i))); i += 4; break;
			case 103:
				meta.put("Varian2", fmt(getFloat(block, i))); i += 4; break;
			case 104: {
				meta.put("CameraExposure", fmt(getFloat(block, i)) + " s"); i += 4;
				if (readAveragingBytes) i += 2; // B1, B2
				break;
			}
			case 105: {
				int end = indexOf0(block, i);
				String title = new String(block, i, end - i).trim();
				if (!title.isEmpty()) meta.put("Title", title);
				i = end + 1;
				break;
			}
			case 106: case 107: case 108: case 109: {
				int end1 = indexOf0(block, i);
				String name = new String(block, i, end1 - i); i = end1 + 1;
				int end2 = indexOf0(block, i);
				String units = new String(block, i, end2 - i); i = end2 + 1;
				meta.put(name + " (" + units + ")", fmt(getFloat(block, i))); i += 4;
				break;
			}
			case 110: {
				int end = indexOf0(block, i);
				String unit = new String(block, i, end - i); i = end + 1;
				meta.put("FOVCalibration", fmt(getFloat(block, i)) + " " + unit); i += 4;
				break;
			}
			case 111:
				meta.put("Phi",   fmt(getFloat(block, i))); i += 4;
				meta.put("Theta", fmt(getFloat(block, i))); i += 4;
				break;
			case 115:
				meta.put("MCPScreenVoltage",  fmt(getFloat(block, i)) + " kV"); i += 4; break;
			case 116:
				meta.put("MCPChannelPlate",   fmt(getFloat(block, i)) + " kV"); i += 4; break;
			default:
				if (tag < 100) {
					int end = indexOf0(block, i);
					String nameAndUnit = new String(block, i, end - i); i = end + 1;
					float val = getFloat(block, i); i += 4;
					if (nameAndUnit.length() > 0) {
						char   unitCode = nameAndUnit.charAt(nameAndUnit.length() - 1);
						String modName  = nameAndUnit.substring(0, nameAndUnit.length() - 1);
						String unit     = (unitCode >= '0' && unitCode <= '9')
								? UNIT_NAMES[unitCode - '0'] : "";
						String key = unit.isEmpty() ? modName : modName + " (" + unit + ")";
						meta.put(key, fmt(val));
					}
				}
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
		return String.format("%.4g", v);
	}

	private static String formatTime(long winFileTime) {
		long ms = (winFileTime - 116444736000000000L) / 10000L;
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
	}
}
