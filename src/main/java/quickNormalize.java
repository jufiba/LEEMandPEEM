/*
 * quickNormalize - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2026 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
 *
 * One-shot beamtime convenience command: chains plotIntensityVsTag and
 * processSpectrum using their last-used settings (stored in ij.Prefs) with
 * no dialogs. Configure the settings once with the individual plugins, then
 * use this command (Shift+Q) for every subsequent dataset.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.Plot;
import ij.gui.Roi;
import ij.measure.Measurements;
import ij.plugin.frame.RoiManager;
import ij.process.ImageStatistics;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

@Plugin(type = Command.class, headless = false,
        menuPath = "Plugins>LEEMandPEEM>Quick Normalize")
public class quickNormalize implements Command {

    @Parameter private LogService log;
    @Parameter private StatusService statusService;

    @Override
    public void run() {

        // ── active stack ──────────────────────────────────────────────────
        final ImagePlus imp = IJ.getImage();
        if (imp == null) {
            IJ.error("Quick Normalize", "No image is open."); return;
        }
        final int n = imp.getStackSize();
        if (n < 2) {
            IJ.error("Quick Normalize", "A stack with at least 2 slices is required."); return;
        }
        final ImageStack stack = imp.getStack();

        // ── read plotIntensityVsTag Prefs ─────────────────────────────────
        final String tagKey   = Prefs.get("LEEMandPEEM.plotVsTag.xTag",     "Frame Number");
        final String xFormula = Prefs.get("LEEMandPEEM.plotVsTag.xFormula", "x");
        final String xLabel   = tagKey;
        final String yTagKey  = Prefs.get("LEEMandPEEM.plotVsTag.yTag",     "None");
        final String yFormula = Prefs.get("LEEMandPEEM.plotVsTag.yFormula", "y");
        final String yLabel   = Prefs.get("LEEMandPEEM.plotVsTag.yLabel",   "Mean Intensity");

        final String  searchKey  = tagKey + "=";
        final boolean hasYTag    = !"None".equals(yTagKey);
        final String  ySearchKey = hasYTag ? yTagKey + "=" : null;

        // validate formulas
        try { plotIntensityVsTag.evalFormula(xFormula, plotIntensityVsTag.varsOf("x", 1.0)); }
        catch (Exception e) {
            IJ.error("Quick Normalize", "Stored X formula is invalid: " + e.getMessage()
                    + "\nRun 'Plot Intensity vs Tag' to reconfigure."); return;
        }
        try { plotIntensityVsTag.evalFormula(yFormula, plotIntensityVsTag.varsOf("y", 1.0, "t", 1.0)); }
        catch (Exception e) {
            IJ.error("Quick Normalize", "Stored Y formula is invalid: " + e.getMessage()
                    + "\nRun 'Plot Intensity vs Tag' to reconfigure."); return;
        }

        // ── collect ROIs ──────────────────────────────────────────────────
        final List<Roi>    rois     = new ArrayList<>();
        final List<String> roiNames = new ArrayList<>();
        final RoiManager rm = RoiManager.getInstance();
        if (rm != null && rm.getCount() > 0) {
            int[] selected = rm.getSelectedIndexes();
            final Roi[] allRois = rm.getRoisAsArray();
            int[] indices = (selected.length > 0) ? selected : new int[allRois.length];
            if (selected.length == 0)
                for (int k = 0; k < allRois.length; k++) indices[k] = k;
            for (int idx : indices) {
                rois.add(allRois[idx]);
                final String name = allRois[idx].getName();
                roiNames.add((name != null && !name.isEmpty()) ? name : "ROI " + (idx + 1));
            }
        } else {
            rois.add(imp.getRoi());
            roiNames.add("All");
        }

        // ── compute raw X and Y ───────────────────────────────────────────
        final double[]   xValues = new double[n];
        final double[][] allY    = new double[rois.size()][n];
        final int savedSlice = imp.getCurrentSlice();

        IJ.showStatus("Quick Normalize: computing intensities…");
        for (int i = 1; i <= n; i++) {
            final String label = stack.getSliceLabel(i);
            final double rawX  = plotIntensityVsTag.extractTagValue(
                    label, searchKey, i, log, tagKey);
            try {
                xValues[i - 1] = plotIntensityVsTag.evalFormula(
                        xFormula, plotIntensityVsTag.varsOf("x", rawX));
            } catch (Exception e) {
                xValues[i - 1] = rawX;
            }

            final double tVal = hasYTag ? plotIntensityVsTag.extractTagValue(
                    label, ySearchKey, i, log, yTagKey) : 0.0;

            imp.setSliceWithoutUpdate(i);
            final ij.process.ImageProcessor ip = imp.getProcessor();
            for (int r = 0; r < rois.size(); r++) {
                ip.setRoi(rois.get(r));
                final ImageStatistics stats = ImageStatistics.getStatistics(
                        ip, Measurements.MEAN, imp.getCalibration());
                try {
                    allY[r][i - 1] = plotIntensityVsTag.evalFormula(
                            yFormula, plotIntensityVsTag.varsOf("y", stats.mean, "t", tVal));
                } catch (Exception e) {
                    allY[r][i - 1] = stats.mean;
                }
            }
            statusService.showProgress(i, n);
        }
        imp.setSlice(savedSlice);
        IJ.showStatus("");

        // ── read processSpectrum Prefs ────────────────────────────────────
        final double  xFirst     = xValues[0];
        final double  xLast      = xValues[n - 1];
        final double  defaultPre  = xValues[Math.min(4, n - 1)];
        final double  defaultPost = xValues[Math.max(n - 5, 0)];
        final double  preEdge    = Prefs.get("LEEMandPEEM.processSpectrum.preEdge",  defaultPre);
        final double  postEdge   = Prefs.get("LEEMandPEEM.processSpectrum.postEdge", defaultPost);
        final boolean doSubtract = Prefs.get("LEEMandPEEM.processSpectrum.subtract",  true);
        final boolean doNormalize= Prefs.get("LEEMandPEEM.processSpectrum.normalize", true);
        final boolean doDiff     = (rois.size() >= 2)
                && Prefs.get("LEEMandPEEM.processSpectrum.difference", false);

        // ── apply normalization ───────────────────────────────────────────
        final int preIdx  = processSpectrum.closestIndex(xValues, preEdge);
        final int postIdx = processSpectrum.closestIndex(xValues, postEdge);

        final double[][] processed = new double[rois.size()][n];
        for (int r = 0; r < rois.size(); r++) {
            final double baseline = doSubtract  ? processSpectrum.meanAround(allY[r], preIdx,  2) : 0.0;
            double       norm     = doNormalize ? processSpectrum.meanAround(allY[r], postIdx, 2) - baseline : 1.0;
            if (norm == 0.0) { norm = 1.0; }
            for (int j = 0; j < n; j++)
                processed[r][j] = (allY[r][j] - baseline) / norm;
        }

        // ── assemble output curves ────────────────────────────────────────
        final List<double[]> outY     = new ArrayList<>();
        final List<String>   outNames = new ArrayList<>();
        for (int r = 0; r < rois.size(); r++) {
            outY.add(processed[r]);
            outNames.add(roiNames.get(r));
        }
        if (doDiff) {
            final double[] diff = new double[n];
            for (int j = 0; j < n; j++) diff[j] = processed[0][j] - processed[1][j];
            outY.add(diff);
            outNames.add(roiNames.get(0) + " \u2212 " + roiNames.get(1));
        }

        final String outYLabel;
        if (doNormalize)      outYLabel = "Normalised intensity";
        else if (doSubtract)  outYLabel = "Intensity (bg subtracted)";
        else                  outYLabel = yLabel;

        // ── build and show plot ───────────────────────────────────────────
        final String[] colors = {"black", "red", "blue", "green", "magenta", "cyan", "orange"};
        final Plot outPlot = new Plot(imp.getTitle() + " (normalised)", xLabel, outYLabel);
        for (int c = 0; c < outY.size(); c++) {
            outPlot.setColor(colors[c % colors.length]);
            outPlot.add("line", xValues, outY.get(c));
            outPlot.setPlotObjectLabel(c, outNames.get(c));
        }
        if (outY.size() > 1) outPlot.addLegend(String.join("\n", outNames));
        outPlot.show();

        IJ.log("Quick Normalize: " + imp.getTitle()
                + " | X=" + tagKey + " | pre=" + String.format("%.3g", preEdge)
                + " | post=" + String.format("%.3g", postEdge));
    }
}
