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
 * Uview reader plugin).  If an ROI is active it is respected; otherwise the
 * whole frame is used.
 */

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.Plot;
import ij.measure.Measurements;
import ij.process.ImageStatistics;

import org.scijava.ItemVisibility;
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

    @Parameter(visibility = ItemVisibility.MESSAGE)
    private final String header =
        "Plots mean intensity vs. a numeric tag embedded in the slice labels. "
        + "Draw an ROI first (optional).";

    @Parameter(label = "Image tag key",
               description = "Tag name exactly as it appears in the slice labels, e.g. \"Start V (V)\"")
    private String tagKey = "Start V (V)";

    @Parameter(label = "X axis label")
    private String xLabel = "Start Voltage (V)";

    @Parameter(label = "Y axis label")
    private String yLabel = "Mean Intensity";

    @Parameter(label = "Plot title")
    private String plotTitle = "Intensity vs Start Voltage";

    @Override
    public void run() {
        final ImagePlus imp = IJ.getImage();
        if (imp == null) {
            log.error("plotIntensityVsTag: no image is open.");
            return;
        }
        final int n = imp.getStackSize();
        if (n < 2) {
            log.error("plotIntensityVsTag: a stack with at least 2 slices is required.");
            return;
        }


        final ImageStack stack    = imp.getStack();
        final String    searchKey = tagKey + "=";
        final double[]  xValues   = new double[n];
        final double[]  yValues   = new double[n];

        final int savedSlice = imp.getCurrentSlice();

        for (int i = 1; i <= n; i++) {

            // --- extract numeric tag value from slice label ---
            final String label = stack.getSliceLabel(i);
            if (label != null) {
                final int idx = label.indexOf(searchKey);
                if (idx >= 0) {
                    final int start = idx + searchKey.length();
                    int end = label.indexOf('\n', start);
                    if (end < 0) end = label.length();
                    try {
                        xValues[i - 1] = Double.parseDouble(label.substring(start, end).trim());
                    } catch (NumberFormatException e) {
                        log.warn("Slice " + i + ": cannot parse value for key \""
                                 + tagKey + "\", using slice index as fallback.");
                        xValues[i - 1] = i;
                    }
                } else {
                    xValues[i - 1] = i; // key not found — fall back to slice number
                }
            } else {
                xValues[i - 1] = i;
            }

            // --- mean intensity, respecting active ROI ---
            imp.setSliceWithoutUpdate(i);
            imp.getProcessor().setRoi(imp.getRoi());
            final ImageStatistics stats = ImageStatistics.getStatistics(
                    imp.getProcessor(), Measurements.MEAN, imp.getCalibration());
            yValues[i - 1] = stats.mean;

            statusService.showProgress(i, n);
        }

        imp.setSlice(savedSlice); // restore original position

        final Plot plot = new Plot(plotTitle, xLabel, yLabel, xValues, yValues);
        plot.show();
    }
}
