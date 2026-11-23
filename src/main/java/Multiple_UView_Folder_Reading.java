/**
 * Multiple_UView_Folder_Reading — opens several experiment folders at once.
 *
 * The user picks a parent folder and a range of numeric endings (e.g. 000 to
 * 005). Every subfolder whose name ends in a number within that range is read
 * with {@link UView_Folder_Reader#openFolder} and shown as its own image/stack,
 * titled with the subfolder name.
 *
 * Appears in Fiji as Plugins > LEEMandPEEM > Multiple UView Folder Reading.
 *
 * @author Juan de la Figuera
 */

import ij.*;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.plugin.PlugIn;

import java.io.File;
import java.util.*;
import java.util.regex.*;

public class Multiple_UView_Folder_Reading implements PlugIn {

	private static final String TITLE = "Multiple UView Folder Reading";

	// Own Prefs keys: reusing the single reader's "Number of images" would let a
	// count saved for one folder silently truncate every experiment here.
	private static final String PREF_FIRST     = "LEEMandPEEM.multiFolderReader.first";
	private static final String PREF_LAST      = "LEEMandPEEM.multiFolderReader.last";
	private static final String PREF_CONTAINS  = "LEEMandPEEM.multiFolderReader.folderContains";
	private static final String PREF_FILTER    = "LEEMandPEEM.multiFolderReader.filter";
	private static final String PREF_START     = "LEEMandPEEM.multiFolderReader.startImg";
	private static final String PREF_NUM       = "LEEMandPEEM.multiFolderReader.numImages";
	private static final String PREF_INCREMENT = "LEEMandPEEM.multiFolderReader.increment";

	private static final Pattern TRAILING_DIGITS = Pattern.compile("(\\d+)$");

	@Override
	public void run(String arg) {
		DirectoryChooser dc = new DirectoryChooser("Parent folder containing the experiment folders");
		String dir = dc.getDirectory();
		if (dir == null) return;
		File parent = new File(dir);

		GenericDialog gd = new GenericDialog(TITLE);
		// String fields so leading zeros ("000") are shown as typed.
		gd.addStringField("First experiment (ending):", ij.Prefs.get(PREF_FIRST, "000"), 8);
		gd.addStringField("Last experiment (ending):",  ij.Prefs.get(PREF_LAST,  "000"), 8);
		gd.addStringField("Folder name contains:",      ij.Prefs.get(PREF_CONTAINS, ""), 20);
		gd.addMessage("Applied to every folder:");
		gd.addStringField("File name contains:",  ij.Prefs.get(PREF_FILTER, ""), 20);
		gd.addNumericField("Starting image:",      ij.Prefs.get(PREF_START, 1), 0);
		gd.addNumericField("Number of images (0 = all):", ij.Prefs.get(PREF_NUM, 0), 0);
		gd.addNumericField("Increment:",           ij.Prefs.get(PREF_INCREMENT, 1), 0);
		// Not persisted, as in UView_Folder_Reader.
		gd.addNumericField("Image within .dat (0 = all):", 0, 0);
		gd.showDialog();
		if (gd.wasCanceled()) return;

		String firstStr   = gd.getNextString().trim();
		String lastStr    = gd.getNextString().trim();
		String contains   = gd.getNextString().trim();
		String filter     = gd.getNextString().trim();
		int    startImg   = Math.max(1, (int) gd.getNextNumber());
		int    numImages  = Math.max(0, (int) gd.getNextNumber());   // 0 = all
		int    increment  = Math.max(1, (int) gd.getNextNumber());
		int    imageInDat = Math.max(0, (int) gd.getNextNumber());   // 0 = all

		int first, last;
		try {
			first = Integer.parseInt(firstStr);
			last  = Integer.parseInt(lastStr);
		} catch (NumberFormatException e) {
			IJ.error(TITLE, "First and last experiment must be numbers (e.g. 000 and 005).");
			return;
		}
		if (first > last) { int t = first; first = last; last = t; }

		ij.Prefs.set(PREF_FIRST,     firstStr);
		ij.Prefs.set(PREF_LAST,      lastStr);
		ij.Prefs.set(PREF_CONTAINS,  contains);
		ij.Prefs.set(PREF_FILTER,    filter);
		ij.Prefs.set(PREF_START,     startImg);
		ij.Prefs.set(PREF_NUM,       numImages);
		ij.Prefs.set(PREF_INCREMENT, increment);

		// --- find subfolders whose trailing number lies in [first, last] ---
		File[] subdirs = parent.listFiles(File::isDirectory);
		if (subdirs == null) subdirs = new File[0];
		TreeMap<Integer, List<File>> bySuffix = new TreeMap<>();
		for (File d : subdirs) {
			String name = d.getName();
			if (!contains.isEmpty() && !name.contains(contains)) continue;
			Matcher m = TRAILING_DIGITS.matcher(name);
			if (!m.find()) continue;
			int n;
			try { n = Integer.parseInt(m.group(1)); }
			catch (NumberFormatException e) { continue; }   // absurdly long digit run
			if (n < first || n > last) continue;
			bySuffix.computeIfAbsent(n, k -> new ArrayList<>()).add(d);
		}

		if (bySuffix.isEmpty()) {
			IJ.error(TITLE, "No subfolders ending in " + firstStr + "–" + lastStr
					+ (contains.isEmpty() ? "" : " and containing \"" + contains + "\"")
					+ " found in:\n" + parent.getAbsolutePath());
			return;
		}

		for (int n = first; n <= last; n++)
			if (!bySuffix.containsKey(n))
				IJ.log(TITLE + ": no folder ending in " + n);

		List<File> folders = new ArrayList<>();
		for (List<File> group : bySuffix.values()) {
			Collections.sort(group);
			if (group.size() > 1)
				IJ.log(TITLE + ": several folders share one ending: " + group
						+ " — use \"Folder name contains\" to pick one series.");
			folders.addAll(group);
		}

		// --- open each folder as its own image/stack ---
		IJ.resetEscape();
		int opened = 0;
		for (int i = 0; i < folders.size(); i++) {
			if (IJ.escapePressed()) {
				IJ.log(TITLE + ": aborted by user after " + opened + " folder(s).");
				break;
			}
			File folder = folders.get(i);
			IJ.showStatus(TITLE + ": " + folder.getName() + " (" + (i + 1) + "/" + folders.size() + ")");
			try {
				ImagePlus imp = UView_Folder_Reader.openFolder(
						folder, filter, startImg, numImages, increment, imageInDat);
				imp.show();
				opened++;
			} catch (UView_Folder_Reader.ReadException e) {
				IJ.log(TITLE + ": skipped " + folder.getName() + " — "
						+ e.getMessage().replace('\n', ' '));
			}
		}
		IJ.showStatus("");

		if (opened == 0)
			IJ.error(TITLE, "None of the " + folders.size() + " matching folder(s) could be read.\n"
					+ "See the Log window for details.");
		else
			IJ.log(TITLE + ": opened " + opened + " of " + folders.size() + " folder(s).");
	}
}
