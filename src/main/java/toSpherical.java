/*
 * toSpherical - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2016 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
 * 
 * 
 */


import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.axis.AxisType;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;

import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.Previewable;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import io.scif.services.DatasetIOService;

import java.lang.Math;

/** toSpherical */
@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>LEEMandPEEM>toSpherical")
public class toSpherical implements Command, Previewable {

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;

	@Parameter
	private DatasetService datasetService;

	@Parameter
	private DatasetIOService datasetIOService;

	@Parameter(visibility = ItemVisibility.MESSAGE)
	private final String header = "Get rho, theta, phi components from an XYZ triplet.";

	@Parameter(label = "Angles in degrees")
	private boolean angle_degrees;
	
	@Parameter(label = "Image X")
	private Dataset d1;

	@Parameter(label = "Image Y")
	private Dataset d2;

	@Parameter(label = "Image Z")
	private Dataset d3;
	
	@Parameter(label = "Result Rho", type = ItemIO.OUTPUT)
	private Dataset result_rho;


	@Parameter(label = "Result Phi (Azimuthal)", type = ItemIO.OUTPUT)
	private Dataset result_phi;
	
	@Parameter(label = "Result Theta (Polar)", type = ItemIO.OUTPUT)
	private Dataset result_theta;

	//public static void main(final String... args) throws Exception {
		// create the ImageJ application context with all available services
	//	final ImageJ ij = net.imagej.Main.launch(args);

	//	ij.command().run(AddTwoDatasets.class, true);
	//}

	@Override
	public void run() {
		
		if (d1.numDimensions() != d2.numDimensions() | d2.numDimensions() != d3.numDimensions()) {
			log.error("Input datasets must have the same number of dimensions.");
			return;
		}
		
		result_rho= getRho(d1,d2,d3);
		result_phi= getAzimuthal(d1,d2,d3,angle_degrees);
		result_theta= getPolar(d1,d2,d3,angle_degrees);
		
	}

	@Override
	public void cancel() {
		log.info("Cancelled");
	}

	@Override
	public void preview() {
		log.info("previews toSpherical");
		statusService.showStatus(header);
	}

	/**
	 * Calculate modulus of three images asummed to be the x,y,z components 
	 * @param d1 x-component 
	 * @param d2 y-component
	 * @param d3 z-component
	 * @return
	 */
	@SuppressWarnings({ "rawtypes" })
	private Dataset getRho(final Dataset d1, final Dataset d2, final Dataset d3) {
		final Dataset result = create(d1, d2, new FloatType(), "Magnitude");

		// sum data into result dataset
		final RandomAccess<? extends RealType> ra1 = d1.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra2 = d2.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra3 = d3.getImgPlus().randomAccess();
		final Cursor<? extends RealType> cursor = result.getImgPlus()
			.localizingCursor();
		final long[] pos1 = new long[d1.numDimensions()];
		final long[] pos2 = new long[d2.numDimensions()];
		final long[] pos3 = new long[d3.numDimensions()];
		while (cursor.hasNext()) {
			cursor.fwd();
			cursor.localize(pos1);
			cursor.localize(pos2);
			cursor.localize(pos3);
			ra1.setPosition(pos1);
			ra2.setPosition(pos2);
			ra3.setPosition(pos3);
			final double sum = Math.sqrt(ra1.get().getRealDouble()*ra1.get().getRealDouble() 
					+ ra2.get().getRealDouble()*ra2.get().getRealDouble() 
					+ ra3.get().getRealDouble()*ra3.get().getRealDouble());
			cursor.get().setReal(sum);
		}

		return result;
	}

	/**
	 * Calculate Polar angle from three images asummed to be the x,y,z components 
	 * @param d1 x-component 
	 * @param d2 y-component
	 * @param d3 z-component
	 * @param angle_degrees Set if you prefer the angles in degrees, otherwise in radians.
	 * @return
	 */
	@SuppressWarnings({ "rawtypes" })
	private Dataset getPolar(final Dataset d1, final Dataset d2, final Dataset d3, final boolean angle_degrees) {
		
		final Dataset result = create(d1, d2, new FloatType(), "Theta (polar)");
		double f=1.0;
		
		if (angle_degrees) {
			f=180.0/Math.PI;
		}
		
		// sum data into result dataset
		final RandomAccess<? extends RealType> ra1 = d1.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra2 = d2.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra3 = d3.getImgPlus().randomAccess();
		final Cursor<? extends RealType> cursor = result.getImgPlus()
			.localizingCursor();
		final long[] pos1 = new long[d1.numDimensions()];
		final long[] pos2 = new long[d2.numDimensions()];
		final long[] pos3 = new long[d3.numDimensions()];
		while (cursor.hasNext()) {
			cursor.fwd();
			cursor.localize(pos1);
			cursor.localize(pos2);
			cursor.localize(pos3);
			ra1.setPosition(pos1);
			ra2.setPosition(pos2);
			ra3.setPosition(pos3);
			final double sum = Math.abs(
					f*(0.5*Math.PI+
					Math.asin(ra3.get().getRealDouble() 
					/ Math.sqrt(ra1.get().getRealDouble()*ra1.get().getRealDouble() 
					+ ra2.get().getRealDouble()*ra2.get().getRealDouble() 
					+ ra3.get().getRealDouble()*ra3.get().getRealDouble()))));
			cursor.get().setReal(sum);
		}

		return result;
	}

	/**
	 * Calculate the azimuthal angle from three images asummed to be the x,y,z components 
	 * @param d1 x-component 
	 * @param d2 y-component
	 * @param d3 z-component
	 * @param angle_degrees Set if you prefer the angles in degrees, otherwise in radians.
	 * @return
	 */
	@SuppressWarnings({ "rawtypes" })
	private Dataset getAzimuthal(final Dataset d1, final Dataset d2, final Dataset d3, final boolean angle_degrees) {
		
		final Dataset result = create(d1, d2, new FloatType(), "Phi (azimuthal)");
		double f=1.0;
		
		if (angle_degrees) {
			f=180.0/Math.PI;
		}
		
		// sum data into result dataset
		final RandomAccess<? extends RealType> ra1 = d1.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra2 = d2.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra3 = d3.getImgPlus().randomAccess();
		final Cursor<? extends RealType> cursor = result.getImgPlus()
			.localizingCursor();
		final long[] pos1 = new long[d1.numDimensions()];
		final long[] pos2 = new long[d2.numDimensions()];
		final long[] pos3 = new long[d3.numDimensions()];
		while (cursor.hasNext()) {
			cursor.fwd();
			cursor.localize(pos1);
			cursor.localize(pos2);
			cursor.localize(pos3);
			ra1.setPosition(pos1);
			ra2.setPosition(pos2);
			ra3.setPosition(pos3);
			double sum = f*(Math.atan2(ra2.get().getRealDouble(), ra1.get().getRealDouble()));
			if (sum<0.0) {
				sum+=f*2.0*Math.PI;
			}
			cursor.get().setReal(sum);
		}

		return result;
	}

	/**
	 * Creates a dataset with bounds constrained by the minimum of the two input
	 * datasets.
	 */
	private <T extends RealType<T> & NativeType<T>> Dataset create(
		final Dataset d1, final Dataset d2, final T type, final String name)
	{
		final int dimCount = Math.min(d1.numDimensions(), d2.numDimensions());
		final long[] dims = new long[dimCount];
		final AxisType[] axes = new AxisType[dimCount];
		for (int i = 0; i < dimCount; i++) {
			dims[i] = Math.min(d1.dimension(i), d2.dimension(i));
			axes[i] = d1.numDimensions() > i ? d1.axis(i).type() : d2.axis(i).type();
		}
		return datasetService.create(type, dims, name, axes);
	}

}

