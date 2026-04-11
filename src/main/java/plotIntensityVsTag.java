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
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.measure.Measurements;
import ij.process.ImageStatistics;

import java.util.ArrayList;
import java.util.List;

import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, headless = false,
        menuPath = "Plugins>LEEMandPEEM>Plot Intensity vs Tag")
public class plotIntensityVsTag implements Command {

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

        // --- dialog ---
        final GenericDialog gd = new GenericDialog("Plot Intensity vs Tag");
        gd.addChoice("Tag", tags.toArray(new String[0]), defaultTag);
        gd.addStringField("Formula (use x for tag value)", "x", 28);
        gd.addStringField("X axis label", defaultUnit, 28);
        gd.addStringField("Y axis label", "Mean Intensity", 28);
        gd.addStringField("Plot title",   "Intensity vs " + defaultTag, 28);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        final String tagKey    = gd.getNextChoice();
        final String formula   = gd.getNextString().trim();
        final String xLabel    = gd.getNextString();
        final String yLabel    = gd.getNextString();
        final String plotTitle = gd.getNextString();
        final String searchKey = tagKey + "=";

        // validate formula with a dummy value before running
        try {
            evalFormula(formula, 1.0);
        } catch (Exception e) {
            IJ.error("plotIntensityVsTag", "Invalid formula: " + e.getMessage());
            return;
        }

        // --- collect data ---
        final double[] xValues = new double[n];
        final double[] yValues = new double[n];
        final int savedSlice = imp.getCurrentSlice();

        for (int i = 1; i <= n; i++) {
            final String label = stack.getSliceLabel(i);
            final double rawValue = extractTagValue(label, searchKey, i, log, tagKey);
            try {
                xValues[i - 1] = evalFormula(formula, rawValue);
            } catch (Exception e) {
                log.warn("Slice " + i + ": formula evaluation failed, using raw value.");
                xValues[i - 1] = rawValue;
            }

            imp.setSliceWithoutUpdate(i);
            imp.getProcessor().setRoi(imp.getRoi());
            final ImageStatistics stats = ImageStatistics.getStatistics(
                    imp.getProcessor(), Measurements.MEAN, imp.getCalibration());
            yValues[i - 1] = stats.mean;

            statusService.showProgress(i, n);
        }

        imp.setSlice(savedSlice);

        final Plot plot = new Plot(plotTitle, xLabel, yLabel, xValues, yValues);
        plot.show();
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
    private static double extractTagValue(final String label, final String searchKey,
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

    /**
     * Evaluate a simple arithmetic formula with variable x.
     * Supports: +  -  *  /  parentheses  unary minus  numeric literals.
     * Example: "350 - x", "x * 0.001", "(x + 5) / 2"
     */
    static double evalFormula(final String formula, final double x) throws Exception {
        return new ExprParser(formula.trim(), x).parse();
    }

    private static class ExprParser {
        private final String expr;
        private final double x;
        private int pos;

        ExprParser(final String expr, final double x) {
            this.expr = expr;
            this.x    = x;
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
            if (c == 'x') { pos++; return x; }
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
