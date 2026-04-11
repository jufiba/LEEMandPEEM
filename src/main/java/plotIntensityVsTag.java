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

        // --- dialog with dropdown of available tags ---
        final GenericDialog gd = new GenericDialog("Plot Intensity vs Tag");
        gd.addChoice("Tag", tags.toArray(new String[0]), tags.get(0));
        gd.addStringField("X axis label", tags.get(0), 28);
        gd.addStringField("Y axis label", "Mean Intensity", 28);
        gd.addStringField("Plot title",   "Intensity vs " + tags.get(0), 28);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        final String tagKey   = gd.getNextChoice();
        final String xLabel   = gd.getNextString();
        final String yLabel   = gd.getNextString();
        final String plotTitle = gd.getNextString();
        final String searchKey = tagKey + "=";

        // --- collect data ---
        final double[] xValues = new double[n];
        final double[] yValues = new double[n];
        final int savedSlice = imp.getCurrentSlice();

        for (int i = 1; i <= n; i++) {
            final String label = stack.getSliceLabel(i);
            xValues[i - 1] = extractTagValue(label, searchKey, i, log, tagKey);

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
     * Extract the numeric value for the given searchKey from a slice label,
     * falling back to the slice index on failure.
     */
    private static double extractTagValue(final String label, final String searchKey,
            final int sliceIndex, final LogService log, final String tagKey) {
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
}
