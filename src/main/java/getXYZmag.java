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
import net.imagej.axis.Axes;
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

//import ij.plugin.CompositeConverter;

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
	private Dataset result;


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
		
		final int dimCount = d1.numDimensions()+1;
		final long[] dims = new long[dimCount];
		final AxisType[] axes = new AxisType[dimCount];
		for (int i = 0; i < dimCount-1; i++) {
			dims[i] = d1.dimension(i);
			axes[i] = d1.axis(i).type();
		}
		axes[dimCount-1]= Axes.CHANNEL;
		dims[dimCount-1]= 3; // We will end with three channel components, corresponding to the X, Y and Z vector components
		
		result=datasetService.create(new FloatType(), dims, "reconstructedXYZ", axes);
		
		getComponent(result,0,d1,d2,d3,b1.getX(),b2.getX(),b3.getX(),"X-axis");
		getComponent(result,1,d1,d2,d3,b1.getY(),b2.getY(),b3.getY(),"Y-axis");
		getComponent(result,2,d1,d2,d3,b1.getZ(),b2.getZ(),b3.getZ(),"Z-axis");
	
		//ij.plugin.CompositeConverter(result.getImgPlus());
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
	void getComponent(Dataset result, long coor, final Dataset d1, final Dataset d2, final Dataset d3, 
			final Double f1, final Double f2, final Double f3, String name) {

		final RandomAccess<? extends RealType> ra1 = d1.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra2 = d2.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra3 = d3.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> res = result.getImgPlus().randomAccess();
		final Cursor<? extends RealType> cursor = d1.getImgPlus()
				.localizingCursor();
		final long[] pos_res = new long[result.numDimensions()];
		final long[] pos = new long[d1.numDimensions()];
		while (cursor.hasNext()) {
			cursor.fwd();
			cursor.localize(pos);
			for (int i=0;i<result.numDimensions()-1;i++) {
				pos_res[i]=pos[i];
			}
			pos_res[result.numDimensions()-1]=coor;
			ra1.setPosition(pos);
			ra2.setPosition(pos);
			ra3.setPosition(pos);
			res.setPosition(pos_res);
			final double sum = f1*ra1.get().getRealDouble() + f2*ra2.get().getRealDouble()+f3*ra3.get().getRealDouble();
			res.get().setReal(sum);
		}
	}
	}
