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
 *   4. Asymmetry              — (I1 - I2)/(I1 + I2) of the input curves, own plot
 *
 * Each curve keeps its own x values, so curves added with "Data>>Add from
 * Plot" (different energy grids) work; curve 2 is interpolated onto curve 1
 * for the difference and asymmetry.
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
import ij.io.SaveDialog;

import java.awt.Window;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String PREF_SAVE_CSV   = "LEEMandPEEM.processSpectrum.saveCsv";
    private static final String PREF_ASYMMETRY  = "LEEMandPEEM.processSpectrum.asymmetry";

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

        // --- read every data curve with its OWN x values ---
        // Curves added with "Data>>Add from Plot" keep their own x arrays, so the
        // plot's ResultsTable becomes X0,Y0,X1,Y1... rather than X,Y0,Y1. Reading
        // the data objects directly handles both layouts.
        final String[] designations = plot.getDataObjectDesignations();
        final List<double[]> xs = new ArrayList<>();
        final List<double[]> ys = new ArrayList<>();
        final List<String>   names = new ArrayList<>();
        for (int i = 0; i < designations.length; i++) {
            final float[][] arr = plot.getDataObjectArrays(i);
            if (arr == null || arr.length < 2 || arr[0] == null || arr[1] == null) continue;
            final int n = Math.min(arr[0].length, arr[1].length);
            final double[] x = new double[n], y = new double[n];
            int k = 0;
            for (int j = 0; j < n; j++) {
                if (Float.isNaN(arr[0][j]) || Float.isNaN(arr[1][j])) continue;
                x[k] = arr[0][j]; y[k] = arr[1][j]; k++;
            }
            if (k == 0) continue;
            xs.add(Arrays.copyOf(x, k));
            ys.add(Arrays.copyOf(y, k));
            // designation looks like "Data Set 2: Mn-015"; keep the name part
            String name = designations[i].replaceFirst("^Data Set \\d+:\\s*", "").trim();
            if (name.isEmpty()) name = "Curve " + (xs.size());
            names.add(name);
        }
        final int nCurves = xs.size();
        if (nCurves == 0) {
            IJ.error("Process Spectrum", "The active plot contains no data.");
            return;
        }
        final String[] curveNames = names.toArray(new String[0]);
        String xHeading = plot.getLabel('x');
        if (xHeading == null || xHeading.isEmpty()) xHeading = "X";

        final double[] x0 = xs.get(0);
        final double xFirst = x0[0];
        final double xLast  = x0[x0.length - 1];

        // --- restore previous settings (default to X value of 5th / 5th-from-last point) ---
        final double  prevPreEdge   = Prefs.get(PREF_PRE_EDGE,
                x0[Math.min(4, x0.length - 1)]);
        final double  prevPostEdge  = Prefs.get(PREF_POST_EDGE,
                x0[Math.max(x0.length - 5, 0)]);
        final boolean prevSubtract  = Prefs.get(PREF_SUBTRACT,   true);
        final boolean prevNormalize = Prefs.get(PREF_NORMALIZE,  true);
        final boolean prevDiff      = Prefs.get(PREF_DIFFERENCE, false);
        final boolean prevAsym      = Prefs.get(PREF_ASYMMETRY,  false);
        final boolean prevSaveCsv   = Prefs.get(PREF_SAVE_CSV,   false);

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
        if (nCurves >= 2) {
            gd.addCheckbox("Difference (curve 1 − curve 2)", prevDiff);
            gd.addCheckbox("Asymmetry (1−2)/(1+2) of the plotted intensities", prevAsym);
        }
        gd.addCheckbox("Save CSV", prevSaveCsv);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        final boolean doSubtract  = gd.getNextBoolean();
        final double  preEdge     = gd.getNextNumber();
        final boolean doNormalize = gd.getNextBoolean();
        final double  postEdge    = gd.getNextNumber();
        final boolean doDiff      = (nCurves >= 2) && gd.getNextBoolean();
        final boolean doAsym      = (nCurves >= 2) && gd.getNextBoolean();
        final boolean saveCsv     = gd.getNextBoolean();

        Prefs.set(PREF_PRE_EDGE,   preEdge);
        Prefs.set(PREF_POST_EDGE,  postEdge);
        Prefs.set(PREF_SUBTRACT,   doSubtract);
        Prefs.set(PREF_NORMALIZE,  doNormalize);
        Prefs.set(PREF_SAVE_CSV,   saveCsv);
        if (nCurves >= 2) {
            Prefs.set(PREF_DIFFERENCE, doDiff);
            Prefs.set(PREF_ASYMMETRY,  doAsym);
        }

        // --- apply operations per curve, on its own x (average over 5 nearest points) ---
        final List<double[]> processed = new ArrayList<>();
        for (int c = 0; c < nCurves; c++) {
            final double[] x = xs.get(c), y = ys.get(c);
            final int preIdx  = closestIndex(x, preEdge);
            final int postIdx = closestIndex(x, postEdge);
            final double baseline = doSubtract  ? meanAround(y, preIdx,  2) : 0.0;
            double       norm     = doNormalize ? meanAround(y, postIdx, 2) - baseline : 1.0;
            if (norm == 0.0) {
                IJ.log("Process Spectrum: curve '" + curveNames[c]
                        + "': post-edge value is zero after subtraction"
                        + " — normalisation skipped.");
                norm = 1.0;
            }
            final double[] out = new double[y.length];
            for (int j = 0; j < y.length; j++) out[j] = (y[j] - baseline) / norm;
            processed.add(out);
        }

        // --- assemble output curves (each with its own x) ---
        final List<double[]> outX     = new ArrayList<>(xs);
        final List<double[]> outY     = new ArrayList<>(processed);
        final List<String>   outNames = new ArrayList<>(Arrays.asList(curveNames));

        if (doDiff) {
            // curve 2 is linearly interpolated onto curve 1's energies
            final double[] b = interpolate(xs.get(1), processed.get(1), x0);
            final double[] diff = new double[x0.length];
            for (int j = 0; j < x0.length; j++) diff[j] = processed.get(0)[j] - b[j];
            outX.add(x0);
            outY.add(diff);
            outNames.add(curveNames[0] + " − " + curveNames[1]);
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
            out.add("line", outX.get(c), outY.get(c));
            out.setPlotObjectLabel(c, outNames.get(c));
        }
        if (outY.size() > 1)
            out.addLegend(String.join("\n", outNames));
        out.show();

        // --- asymmetry: (I1 - I2) / (I1 + I2) of the curves as plotted ---
        // Computed on the input intensities, not the processed ones: after
        // pre-edge subtraction the denominator goes to ~0 below the edge.
        double[] asym = null;
        String asymName = null;
        if (doAsym) {
            final double[] a = ys.get(0);
            final double[] b = interpolate(xs.get(1), ys.get(1), x0);
            asym = new double[x0.length];
            for (int j = 0; j < x0.length; j++) {
                final double s = a[j] + b[j];
                asym[j] = (s == 0.0 || Double.isNaN(b[j])) ? Double.NaN : (a[j] - b[j]) / s;
            }
            asymName = "(" + curveNames[0] + " − " + curveNames[1] + ") / ("
                    + curveNames[0] + " + " + curveNames[1] + ")";
            final Plot ap = new Plot(title.replaceFirst(" \\(processed\\)$", "") + " (asymmetry)",
                    xHeading, "Asymmetry");
            ap.setColor("blue");
            ap.add("line", x0, asym);
            ap.setPlotObjectLabel(0, asymName);
            ap.addLegend(asymName);
            ap.show();
        }

        // --- save CSV if requested ---
        if (saveCsv) {
            final SaveDialog sd = new SaveDialog("Save processed spectrum as CSV",
                    title, ".csv");
            if (sd.getFileName() != null) {
                final String path = sd.getDirectory() + sd.getFileName();
                if (asym != null) { outX.add(x0); outY.add(asym); outNames.add(asymName); }
                try (BufferedWriter bw = new BufferedWriter(new FileWriter(path))) {
                    writeCsv(bw, xHeading, outX, outY, outNames);
                    IJ.log("processSpectrum: saved CSV to " + path);
                } catch (IOException e) {
                    IJ.error("Process Spectrum", "Could not save CSV:\n" + e.getMessage());
                }
            }
        }
    }

    /**
     * One shared X column when every curve has the same energies; otherwise an
     * X column per curve (shorter curves padded with empty cells).
     */
    private static void writeCsv(BufferedWriter bw, String xHeading, List<double[]> xs,
                                 List<double[]> ys, List<String> names) throws IOException {
        boolean shared = true;
        for (double[] x : xs) shared &= Arrays.equals(x, xs.get(0));
        if (shared) {
            bw.write(csvField(xHeading));
            for (String n : names) bw.write("," + csvField(n));
            bw.newLine();
            for (int i = 0; i < xs.get(0).length; i++) {
                bw.write(String.valueOf(xs.get(0)[i]));
                for (double[] y : ys) bw.write("," + y[i]);
                bw.newLine();
            }
            return;
        }
        int rows = 0;
        for (double[] x : xs) rows = Math.max(rows, x.length);
        for (int c = 0; c < names.size(); c++) {
            if (c > 0) bw.write(",");
            bw.write(csvField(xHeading + " [" + names.get(c) + "]") + "," + csvField(names.get(c)));
        }
        bw.newLine();
        for (int i = 0; i < rows; i++) {
            for (int c = 0; c < names.size(); c++) {
                if (c > 0) bw.write(",");
                if (i < xs.get(c).length) bw.write(xs.get(c)[i] + "," + ys.get(c)[i]);
                else bw.write(",");
            }
            bw.newLine();
        }
    }

    private static String csvField(String s) {
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    /**
     * Linear interpolation of (x, y) at the points xi; NaN outside x's range.
     * x need not be sorted ascending (scans can run high-to-low).
     */
    static double[] interpolate(double[] x, double[] y, double[] xi) {
        final Integer[] order = new Integer[x.length];
        for (int i = 0; i < x.length; i++) order[i] = i;
        Arrays.sort(order, (p, q) -> Double.compare(x[p], x[q]));
        final double[] sx = new double[x.length], sy = new double[x.length];
        for (int i = 0; i < x.length; i++) { sx[i] = x[order[i]]; sy[i] = y[order[i]]; }

        final double[] out = new double[xi.length];
        for (int j = 0; j < xi.length; j++) {
            final double t = xi[j];
            if (sx.length == 0 || t < sx[0] || t > sx[sx.length - 1]) { out[j] = Double.NaN; continue; }
            int k = Arrays.binarySearch(sx, t);
            if (k >= 0) { out[j] = sy[k]; continue; }
            k = -k - 1;                       // sx[k-1] < t < sx[k]
            final double f = (t - sx[k - 1]) / (sx[k] - sx[k - 1]);
            out[j] = sy[k - 1] + f * (sy[k] - sy[k - 1]);
        }
        return out;
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
