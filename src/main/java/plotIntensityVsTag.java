/*
 * plotIntensityVsTag - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2026 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
 *
 * Plots the mean intensity of each slice in a stack against a numeric value
 * extracted from the slice label (e.g. "Start V (V)=42.0" written by the
 * Uview reader plugin).  An optional formula can be applied to the tag values
 * (e.g. "350 - x").  If an ROI is active it is respected; otherwise the
 * whole frame is used.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.gui.Roi;
import ij.io.SaveDialog;
import ij.measure.Measurements;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, headless = false,
        menuPath = "Plugins>LEEMandPEEM>Plot Intensity vs Tag")
public class plotIntensityVsTag implements Command {

    private static final String PREF_X_TAG      = "LEEMandPEEM.plotVsTag.xTag";
    private static final String PREF_X_FORMULA  = "LEEMandPEEM.plotVsTag.xFormula";
    private static final String PREF_Y_TAG      = "LEEMandPEEM.plotVsTag.yTag";
    private static final String PREF_Y_FORMULA  = "LEEMandPEEM.plotVsTag.yFormula";
    private static final String PREF_Y_LABEL    = "LEEMandPEEM.plotVsTag.yLabel";
    private static final String PREF_SAVE_CSV   = "LEEMandPEEM.plotVsTag.saveCsv";

    @Parameter
    private LogService log;

    @Parameter
    private StatusService statusService;

    @Override
    public void run() {
        final ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.error("plotIntensityVsTag", "No image is open.");
            return;
        }
        final int n = imp.getStackSize();
        if (n < 2) {
            IJ.error("plotIntensityVsTag", "A stack with at least 2 slices is required.");
            return;
        }

        final ImageStack stack = imp.getStack();

        // --- parse numeric tags from the first slice label ---
        final List<String> tags = numericTagsFromLabel(stack.getSliceLabel(1));
        if (tags.isEmpty()) {
            IJ.error("plotIntensityVsTag",
                "No numeric tags found in the slice labels.\n" +
                "Open the stack with UView Folder Reader to embed metadata.");
            return;
        }

        tags.add(0, "Frame Number");

        final String defaultTag  = tags.get(0);
        final String defaultUnit = unitFromKey(defaultTag);

        // Y-tag list: "None" + same numeric tags
        final List<String> yTagOptions = new ArrayList<>();
        yTagOptions.add("None");
        yTagOptions.addAll(tags);

        // --- restore last-used values (fall back to defaults if tag no longer exists) ---
        final String  prevXTag     = pickFromList(tags,       Prefs.get(PREF_X_TAG,     defaultTag), defaultTag);
        final String  prevXFormula = Prefs.get(PREF_X_FORMULA, "x");
        final String  prevYTag     = pickFromList(yTagOptions, Prefs.get(PREF_Y_TAG,    "None"),     "None");
        final String  prevYFormula = Prefs.get(PREF_Y_FORMULA, "y");
        final String  prevYLabel   = Prefs.get(PREF_Y_LABEL,   "Mean Intensity");
        final boolean prevSaveCsv  = Prefs.get(PREF_SAVE_CSV,  false);

        // --- dialog ---
        final GenericDialog gd = new GenericDialog("Plot Intensity vs Tag");
        gd.addChoice("X tag",                              tags.toArray(new String[0]), prevXTag);
        gd.addStringField("X formula (use x for tag value)", prevXFormula, 28);
        gd.addStringField("X axis label",                  prevXTag, 28);  // pre-filled from tag, editable
        gd.addChoice("Y tag (for Y formula, use t)",       yTagOptions.toArray(new String[0]), prevYTag);
        gd.addStringField("Y formula (use y for intensity, t for Y tag)", prevYFormula, 28);
        gd.addStringField("Y axis label",                  prevYLabel, 28);
        gd.addCheckbox("Save CSV", prevSaveCsv);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        final String  tagKey    = gd.getNextChoice();
        final String  xFormula  = gd.getNextString().trim();
        final String  xLabel    = gd.getNextString();
        final String  yTagKey   = gd.getNextChoice();
        final String  yFormula  = gd.getNextString().trim();
        final String  yLabel    = gd.getNextString();
        final boolean saveCsv   = gd.getNextBoolean();
        final String  plotTitle = imp.getTitle();

        // --- persist choices for next run ---
        Prefs.set(PREF_X_TAG,     tagKey);
        Prefs.set(PREF_X_FORMULA, xFormula);
        Prefs.set(PREF_Y_TAG,     yTagKey);
        Prefs.set(PREF_Y_FORMULA, yFormula);
        Prefs.set(PREF_Y_LABEL,   yLabel);
        Prefs.set(PREF_SAVE_CSV,  saveCsv);
        final String searchKey = tagKey + "=";
        final boolean hasYTag  = !"None".equals(yTagKey);
        final String ySearchKey = hasYTag ? yTagKey + "=" : null;

        // validate formulas with dummy values before running
        try {
            evalFormula(xFormula, varsOf("x", 1.0));
        } catch (Exception e) {
            IJ.error("plotIntensityVsTag", "Invalid X formula: " + e.getMessage());
            return;
        }
        try {
            evalFormula(yFormula, varsOf("y", 1.0, "t", 1.0));
        } catch (Exception e) {
            IJ.error("plotIntensityVsTag", "Invalid Y formula: " + e.getMessage());
            return;
        }

        // --- collect ROIs to plot ---
        final List<Roi>    rois     = new ArrayList<>();
        final List<String> roiNames = new ArrayList<>();
        final RoiManager rm = RoiManager.getInstance();
        if (rm != null && rm.getCount() > 0) {
            int[] selected = rm.getSelectedIndexes();
            final Roi[] allRois = rm.getRoisAsArray();
            int[] indices = (selected.length > 0) ? selected
                    : new int[allRois.length];
            if (selected.length == 0)
                for (int k = 0; k < allRois.length; k++) indices[k] = k;
            for (int idx : indices) {
                rois.add(allRois[idx]);
                final String name = allRois[idx].getName();
                roiNames.add((name != null && !name.isEmpty()) ? name : "ROI " + (idx + 1));
            }
        } else {
            rois.add(imp.getRoi()); // null = whole image
            roiNames.add("All");
        }

        // --- collect data ---
        final double[]   xValues = new double[n];
        final double[][] allY    = new double[rois.size()][n];
        final int savedSlice = imp.getCurrentSlice();

        for (int i = 1; i <= n; i++) {
            final String label = stack.getSliceLabel(i);
            final double rawX = extractTagValue(label, searchKey, i, log, tagKey);
            try {
                xValues[i - 1] = evalFormula(xFormula, varsOf("x", rawX));
            } catch (Exception e) {
                log.warn("Slice " + i + ": X formula evaluation failed, using raw value.");
                xValues[i - 1] = rawX;
            }

            final double tVal = hasYTag
                    ? extractTagValue(label, ySearchKey, i, log, yTagKey)
                    : 0.0;

            imp.setSliceWithoutUpdate(i);
            final ij.process.ImageProcessor ip = imp.getProcessor();
            for (int r = 0; r < rois.size(); r++) {
                ip.setRoi(rois.get(r));
                final ImageStatistics stats = ImageStatistics.getStatistics(
                        ip, Measurements.MEAN, imp.getCalibration());
                try {
                    allY[r][i - 1] = evalFormula(yFormula,
                            varsOf("y", stats.mean, "t", tVal));
                } catch (Exception e) {
                    log.warn("Slice " + i + " ROI " + r + ": Y formula failed, using mean.");
                    allY[r][i - 1] = stats.mean;
                }
            }
            statusService.showProgress(i, n);
        }

        imp.setSlice(savedSlice);

        // --- build plot ---
        final String[] colors = {"black", "red", "blue", "green", "magenta", "cyan", "orange"};
        final Plot plot = new Plot(plotTitle, xLabel, yLabel);
        for (int r = 0; r < rois.size(); r++) {
            plot.setColor(colors[r % colors.length]);
            plot.add("line", xValues, allY[r]);
            plot.setPlotObjectLabel(r, roiNames.get(r));
        }
        if (rois.size() > 1)
            plot.addLegend(String.join("\n", roiNames));
        plot.show();

        // --- save CSV if requested ---
        if (saveCsv) {
            final SaveDialog sd = new SaveDialog("Save plot data as CSV",
                    plotTitle + "_plot", ".csv");
            if (sd.getFileName() != null) {
                final String path = sd.getDirectory() + sd.getFileName();
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
                    // header
                    bw.write(xLabel);
                    for (String name : roiNames) bw.write("," + name);
                    bw.newLine();
                    // data rows
                    for (int i = 0; i < n; i++) {
                        bw.write(String.valueOf(xValues[i]));
                        for (int r = 0; r < rois.size(); r++)
                            bw.write("," + allY[r][i]);
                        bw.newLine();
                    }
                    IJ.log("plotIntensityVsTag: saved CSV to " + path);
                } catch (IOException e) {
                    IJ.error("plotIntensityVsTag", "Could not save CSV:\n" + e.getMessage());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Parse all key=value lines from a slice label and return the keys whose
     * values are numeric (parseable as double).
     */
    private static List<String> numericTagsFromLabel(final String label) {
        final List<String> tags = new ArrayList<>();
        if (label == null) return tags;
        for (final String line : label.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq <= 0) continue;
            final String key = line.substring(0, eq).trim();
            final String val = line.substring(eq + 1).trim();
            try {
                Double.parseDouble(val);
                tags.add(key);
            } catch (NumberFormatException e) {
                // skip non-numeric entries (e.g. Date)
            }
        }
        return tags;
    }

    /**
     * Extract the unit string from a tag key of the form "Name (unit)".
     * Returns the full key if no parenthesised unit is found.
     */
    private static String unitFromKey(final String key) {
        if ("Frame Number".equals(key)) return "Frame";
        final int open  = key.lastIndexOf('(');
        final int close = key.lastIndexOf(')');
        if (open >= 0 && close > open)
            return key.substring(open + 1, close);
        return key;
    }

    /**
     * Extract the numeric value for searchKey from a slice label,
     * falling back to the slice index on failure.
     * If tagKey is "Frame Number" the slice index is returned directly.
     */
    static double extractTagValue(final String label, final String searchKey,
            final int sliceIndex, final LogService log, final String tagKey) {
        if ("Frame Number".equals(tagKey)) return sliceIndex;
        if (label == null) return sliceIndex;
        final int idx = label.indexOf(searchKey);
        if (idx < 0) return sliceIndex;
        final int start = idx + searchKey.length();
        int end = label.indexOf('\n', start);
        if (end < 0) end = label.length();
        try {
            return Double.parseDouble(label.substring(start, end).trim());
        } catch (NumberFormatException e) {
            log.warn("Slice " + sliceIndex + ": cannot parse value for key \""
                     + tagKey + "\", using slice index as fallback.");
            return sliceIndex;
        }
    }

    /** Return {@code value} if it is present in {@code list}, otherwise {@code fallback}. */
    private static String pickFromList(List<String> list, String value, String fallback) {
        return list.contains(value) ? value : fallback;
    }

    /** Build a variable map from name/value pairs: varsOf("x", 1.0, "y", 2.0, ...). */
    static Map<String, Double> varsOf(Object... pairs) {
        final Map<String, Double> m = new HashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2)
            m.put((String) pairs[i], (Double) pairs[i + 1]);
        return m;
    }

    /**
     * Evaluate a simple arithmetic formula with named variables supplied via a map.
     * Supports: +  -  *  /  parentheses  unary minus  numeric literals.
     * Variables can be any identifier (single letter or word) present in the map.
     * Examples: "350 - x", "y / t", "(y - 100) / t * 1000"
     */
    static double evalFormula(final String formula, final Map<String, Double> vars) throws Exception {
        return new ExprParser(formula.trim(), vars).parse();
    }

    private static class ExprParser {
        private final String              expr;
        private final Map<String, Double> vars;
        private int pos;

        ExprParser(final String expr, final Map<String, Double> vars) {
            this.expr = expr;
            this.vars = vars;
            this.pos  = 0;
        }

        double parse() throws Exception {
            final double result = parseExpr();
            skipSpaces();
            if (pos < expr.length())
                throw new Exception("Unexpected character at position " + pos
                        + ": '" + expr.charAt(pos) + "'");
            return result;
        }

        private double parseExpr() throws Exception {
            double result = parseTerm();
            while (true) {
                skipSpaces();
                if (pos >= expr.length()) break;
                final char c = expr.charAt(pos);
                if      (c == '+') { pos++; result += parseTerm(); }
                else if (c == '-') { pos++; result -= parseTerm(); }
                else break;
            }
            return result;
        }

        private double parseTerm() throws Exception {
            double result = parseFactor();
            while (true) {
                skipSpaces();
                if (pos >= expr.length()) break;
                final char c = expr.charAt(pos);
                if      (c == '*') { pos++; result *= parseFactor(); }
                else if (c == '/') { pos++; result /= parseFactor(); }
                else break;
            }
            return result;
        }

        private double parseFactor() throws Exception {
            skipSpaces();
            if (pos >= expr.length()) throw new Exception("Unexpected end of expression");
            final char c = expr.charAt(pos);
            if (c == '(') {
                pos++;
                final double result = parseExpr();
                skipSpaces();
                if (pos >= expr.length() || expr.charAt(pos) != ')')
                    throw new Exception("Expected closing parenthesis");
                pos++;
                return result;
            }
            if (c == '-') { pos++; return -parseFactor(); }
            if (c == '+') { pos++; return  parseFactor(); }
            if (Character.isLetter(c)) {
                final int start = pos;
                while (pos < expr.length() && Character.isLetterOrDigit(expr.charAt(pos))) pos++;
                final String name = expr.substring(start, pos);
                if (!vars.containsKey(name))
                    throw new Exception("Unknown variable: '" + name + "'");
                return vars.get(name);
            }
            if (Character.isDigit(c) || c == '.') {
                final int start = pos;
                while (pos < expr.length()) {
                    final char d = expr.charAt(pos);
                    if (Character.isDigit(d) || d == '.') { pos++; continue; }
                    if ((d == 'e' || d == 'E') && pos > start) { pos++; continue; }
                    if ((d == '+' || d == '-') && pos > start &&
                            (expr.charAt(pos - 1) == 'e' || expr.charAt(pos - 1) == 'E')) {
                        pos++; continue;
                    }
                    break;
                }
                return Double.parseDouble(expr.substring(start, pos));
            }
            throw new Exception("Unexpected character: '" + c + "'");
        }

        private void skipSpaces() {
            while (pos < expr.length() && expr.charAt(pos) == ' ') pos++;
        }
    }
}
