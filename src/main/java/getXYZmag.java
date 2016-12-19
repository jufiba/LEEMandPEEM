/*
 * GetXYZ - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2016 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
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

//import io.scif.services.DatasetIOService;

import java.lang.Math;

//import Vector3d;

/** getXYZmag */
@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>LEEMandPEEM>getXYZmag")
public class getXYZmag implements Command, Previewable {

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;

	@Parameter
	private DatasetService datasetService;

	//@Parameter
	//private DatasetIOService datasetIOService;

	@Parameter(visibility = ItemVisibility.MESSAGE)
	private final String header = "Get XYZ components from three images with components along arbitrary directions";

	@Parameter(label = "Angle in degrees")
	private Boolean angle_degrees;
	
	@Parameter(label = "Image 1")
	private Dataset d1;

	@Parameter(label="Azimuthal angle (phi) 1")
	private Double phi1;

	@Parameter(label="Polar angle (theta) 1")
	private Double theta1;

	@Parameter(label = "Image 2")
	private Dataset d2;

	@Parameter(label="Azimuthal angle (phi) 2")
	private Double phi2;

	@Parameter(label="Polar angle (theta) 2")
	private Double theta2;
	
	@Parameter(label = "Image 3")
	private Dataset d3;

	@Parameter(label="Azimuthal angle (phi) 3")
	private Double phi3;
	
	@Parameter(label="Polar angle (theta) 3")
	private Double theta3;

	@Parameter(label = "Result X", type = ItemIO.OUTPUT)
	private Dataset result_x;

	@Parameter(label = "Result Y", type = ItemIO.OUTPUT)
	private Dataset result_y;

	@Parameter(label = "Result Z", type = ItemIO.OUTPUT)
	private Dataset result_z;


	//public static void main(final String... args) throws Exception {
		// create the ImageJ application context with all available services
	//	final ImageJ ij = net.imagej.Main.launch(args);

	//	ij.command().run(AddTwoDatasets.class, true);
	//}

	@Override
	public void run() {
		if (angle_degrees) {
			theta1*=Math.PI/180;
			theta2*=Math.PI/180;
			theta3*=Math.PI/180;
			phi1*=Math.PI/180;
			phi2*=Math.PI/180;
			phi3*=Math.PI/180;
		}
	
		Vector3d a1= Vector3d.Spherical(phi1,theta1);
		Vector3d a2= Vector3d.Spherical(phi2,theta2);
		Vector3d a3= Vector3d.Spherical(phi3,theta3);
		
		Vector3d b1= Vector3d.perp(a1,a2,a3);
		Vector3d b2= Vector3d.perp(a2,a3,a1);
		Vector3d b3= Vector3d.perp(a3,a1,a2);
		
		if (d1.numDimensions() != d2.numDimensions() | d2.numDimensions() != d3.numDimensions()) {
			log.error("Input datasets must have the same number of dimensions.");
			return;
		}
		
		result_x= getComponent(d1,d2,d3,b1.getX(),b2.getX(),b3.getX(),"X-axis");
		result_y= getComponent(d1,d2,d3,b1.getY(),b2.getY(),b3.getY(),"Y-axis");
		result_z= getComponent(d1,d2,d3,b1.getZ(),b2.getZ(),b3.getZ(),"Z-axis");
	
	}

	@Override
	public void cancel() {
		log.info("Cancelled");
	}

	@Override
	public void preview() {
		log.info("make XYZ datasets out of arbitrary datasets");
		statusService.showStatus(header);
	}

	/**
	 * Make a new image from a factor-weighted sum of three separate images 
	 * @param d1 
	 * @param d2
	 * @param d3
	 * @param f1
	 * @param f2
	 * @param f3
	 * @param name
	 * @return
	 */
	@SuppressWarnings({ "rawtypes" })
	private Dataset getComponent(final Dataset d1, final Dataset d2, final Dataset d3, 
			final Double f1, final Double f2, final Double f3, String name) {
		final Dataset result = create(d1, d2, new FloatType(), name);

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
			final double sum = f1*ra1.get().getRealDouble() + f2*ra2.get().getRealDouble()+f3*ra3.get().getRealDouble();
			cursor.get().setReal(sum);
		}

		return result;
	}


	/**
	 * Creates a dataset with bounds constrained by the minimum of the two input
	 * datasets.
	 */
	private <T extends RealType<T> & NativeType<T>> Dataset create(
		final Dataset d1, final Dataset d2, final T type, String name)
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
