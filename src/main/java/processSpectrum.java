/*
 * processSpectrum - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2026 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
 *
 * Post-processes spectra from an active Plot window (produced by
 * plotIntensityVsTag). Operations applied in order:
 *   1. Pre-edge subtraction  — subtract the intensity at a given energy
 *   2. Post-edge normalisation — divide by the intensity at a given energy
 *   3. Difference             — curve 1 minus curve 2 (two-curve plots only)
 *
 * The processed curves are shown in a new plot window.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.measure.ResultsTable;

import java.awt.Window;
import java.util.ArrayList;
import java.util.List;

import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, headless = false,
        menuPath = "Plugins>LEEMandPEEM>Process Spectrum")
public class processSpectrum implements Command {

    private static final String PREF_PRE_EDGE   = "LEEMandPEEM.processSpectrum.preEdge";
    private static final String PREF_POST_EDGE  = "LEEMandPEEM.processSpectrum.postEdge";
    private static final String PREF_SUBTRACT   = "LEEMandPEEM.processSpectrum.subtract";
    private static final String PREF_NORMALIZE  = "LEEMandPEEM.processSpectrum.normalize";
    private static final String PREF_DIFFERENCE = "LEEMandPEEM.processSpectrum.difference";

    @Parameter
    private LogService log;

    @Override
    public void run() {

        // --- locate active plot window ---
        final PlotWindow pw = getActivePlotWindow();
        if (pw == null) {
            IJ.error("Process Spectrum",
                    "No plot window found.\nRun 'Plot Intensity vs Tag' first.");
            return;
        }

        final Plot plot = pw.getPlot();
        final ResultsTable rt = plot.getResultsTable();
        if (rt == null || rt.size() == 0) {
            IJ.error("Process Spectrum", "The active plot contains no data.");
            return;
        }

        // --- parse columns ---
        final String[] headings = rt.getHeadings();
        if (headings.length < 2) {
            IJ.error("Process Spectrum",
                    "Plot must have at least one X column and one Y column.");
            return;
        }

        final String   xHeading  = headings[0];
        final int      nCurves   = headings.length - 1;
        final String[] curveNames = new String[nCurves];
        for (int c = 0; c < nCurves; c++) curveNames[c] = headings[c + 1];

        final double[] xValues = getColumn(rt, xHeading);
        final double[][] yValues = new double[nCurves][];
        for (int c = 0; c < nCurves; c++)
            yValues[c] = getColumn(rt, curveNames[c]);

        final double xFirst = xValues[0];
        final double xLast  = xValues[xValues.length - 1];
        final double xMin   = Math.min(xFirst, xLast);
        final double xMax   = Math.max(xFirst, xLast);

        // --- restore previous settings (default to X value of 5th / 5th-from-last point) ---
        final double  prevPreEdge   = Prefs.get(PREF_PRE_EDGE,
                xValues[Math.min(4, xValues.length - 1)]);
        final double  prevPostEdge  = Prefs.get(PREF_POST_EDGE,
                xValues[Math.max(xValues.length - 5, 0)]);
        final boolean prevSubtract  = Prefs.get(PREF_SUBTRACT,   true);
        final boolean prevNormalize = Prefs.get(PREF_NORMALIZE,  true);
        final boolean prevDiff      = Prefs.get(PREF_DIFFERENCE, false);

        // --- dialog ---
        final GenericDialog gd = new GenericDialog("Process Spectrum");
        gd.addMessage("Plot:   " + pw.getTitle());
        gd.addMessage("Curves: " + String.join(", ", curveNames)
                + "    X range: " + String.format("%.3g", xFirst)
                + " – " + String.format("%.3g", xLast));
        gd.addCheckbox("Pre-edge subtraction",      prevSubtract);
        gd.addNumericField("  Pre-edge energy",     prevPreEdge,  3, 10, "");
        gd.addCheckbox("Post-edge normalisation",   prevNormalize);
        gd.addNumericField("  Post-edge energy",    prevPostEdge, 3, 10, "");
        if (nCurves >= 2)
            gd.addCheckbox("Show difference (curve 1 \u2212 curve 2)", prevDiff);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        final boolean doSubtract  = gd.getNextBoolean();
        final double  preEdge     = gd.getNextNumber();
        final boolean doNormalize = gd.getNextBoolean();
        final double  postEdge    = gd.getNextNumber();
        final boolean doDiff      = (nCurves >= 2) && gd.getNextBoolean();

        Prefs.set(PREF_PRE_EDGE,   preEdge);
        Prefs.set(PREF_POST_EDGE,  postEdge);
        Prefs.set(PREF_SUBTRACT,   doSubtract);
        Prefs.set(PREF_NORMALIZE,  doNormalize);
        if (nCurves >= 2) Prefs.set(PREF_DIFFERENCE, doDiff);

        // --- apply operations (average over 5 nearest points for robustness) ---
        final int preIdx  = closestIndex(xValues, preEdge);
        final int postIdx = closestIndex(xValues, postEdge);

        final double[][] processed = new double[nCurves][xValues.length];
        for (int c = 0; c < nCurves; c++) {
            final double baseline = doSubtract  ? meanAround(yValues[c], preIdx,  2) : 0.0;
            double       norm     = doNormalize ? meanAround(yValues[c], postIdx, 2) - baseline : 1.0;
            if (norm == 0.0) {
                log.warn("Curve '" + curveNames[c]
                        + "': post-edge value is zero after subtraction"
                        + " — normalisation skipped.");
                norm = 1.0;
            }
            for (int j = 0; j < xValues.length; j++)
                processed[c][j] = (yValues[c][j] - baseline) / norm;
        }

        // --- assemble output curves ---
        final List<double[]> outY     = new ArrayList<>();
        final List<String>   outNames = new ArrayList<>();

        for (int c = 0; c < nCurves; c++) {
            outY.add(processed[c]);
            outNames.add(curveNames[c]);
        }

        if (doDiff) {
            final double[] diff = new double[xValues.length];
            for (int j = 0; j < xValues.length; j++)
                diff[j] = processed[0][j] - processed[1][j];
            outY.add(diff);
            outNames.add(curveNames[0] + " \u2212 " + curveNames[1]);
        }

        // --- build Y axis label ---
        final String yLabel;
        if (doNormalize)     yLabel = "Normalised intensity";
        else if (doSubtract) yLabel = "Intensity (bg subtracted)";
        else                 yLabel = plot.getLabel('y');

        // --- show new plot ---
        final String[] colors = {"black", "red", "blue", "green", "magenta", "cyan", "orange"};
        final String title = pw.getTitle().replaceFirst(" \\(processed.*\\)$", "")
                + " (processed)";
        final Plot out = new Plot(title, xHeading, yLabel);
        for (int c = 0; c < outY.size(); c++) {
            out.setColor(colors[c % colors.length]);
            out.add("line", xValues, outY.get(c));
            out.setPlotObjectLabel(c, outNames.get(c));
        }
        if (outY.size() > 1)
            out.addLegend(String.join("\n", outNames));
        out.show();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Find the most recent PlotWindow, preferring the active window. */
    private static PlotWindow getActivePlotWindow() {
        final Window active = WindowManager.getActiveWindow();
        if (active instanceof PlotWindow) return (PlotWindow) active;
        // scan all image windows newest-first
        final int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int i = ids.length - 1; i >= 0; i--) {
                final ImagePlus imp = WindowManager.getImage(ids[i]);
                if (imp != null && imp.getWindow() instanceof PlotWindow)
                    return (PlotWindow) imp.getWindow();
            }
        }
        return null;
    }

    /** Return column data as double[], using getColumnAsDoubles where available. */
    private static double[] getColumn(ResultsTable rt, String heading) {
        final int col = rt.getColumnIndex(heading);
        // getColumnAsDoubles is available in recent Fiji; fall back to float[]
        try {
            final double[] d = rt.getColumnAsDoubles(col);
            if (d != null) return d;
        } catch (Exception ignored) {}
        final float[] f = rt.getColumn(col);
        if (f == null) return new double[rt.size()];
        final double[] d = new double[f.length];
        for (int i = 0; i < f.length; i++) d[i] = f[i];
        return d;
    }

    /** Average y[centerIdx ± halfWindow], clamped to array bounds. */
    static double meanAround(double[] y, int centerIdx, int halfWindow) {
        final int from = Math.max(0, centerIdx - halfWindow);
        final int to   = Math.min(y.length - 1, centerIdx + halfWindow);
        double sum = 0;
        for (int j = from; j <= to; j++) sum += y[j];
        return sum / (to - from + 1);
    }

    /** Return the index of the element in x closest to target. */
    static int closestIndex(double[] x, double target) {
        int    idx     = 0;
        double minDist = Math.abs(x[0] - target);
        for (int i = 1; i < x.length; i++) {
            final double d = Math.abs(x[i] - target);
            if (d < minDist) { minDist = d; idx = i; }
        }
        return idx;
    }
}
