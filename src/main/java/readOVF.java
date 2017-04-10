/*
 * writeOVF - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2016 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
 * 
 */

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Date;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.axis.AxisType;
import net.imagej.ops.OpService;
import net.imagej.ops.Ops;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RandomAccess;

import net.imglib2.img.Img;
import net.imglib2.meta.CalibratedSpace;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imagej.axis.Axes;
import net.imagej.axis.CalibratedAxis;
import net.imagej.axis.DefaultLinearAxis;
import net.imagej.axis.LinearAxis;
import net.imagej.axis.AxisType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ItemIO;
import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.Previewable;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import io.scif.MetadataService;

//import net.imagej.*;

import io.scif.services.DatasetIOService;

@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>LEEMandPEEM>readOVF")
public class readOVF implements Command, Previewable {

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;

	@Parameter
	private DatasetService datasetService;

	//@Parameter
	//private DatasetIOService datasetIOService;

	@Parameter(visibility = ItemVisibility.MESSAGE)
	private final String header = "Read an slice from an OVF 1.0 OOMMF vector file into three images assumed to be the XYZ components";

	@Parameter(label = "OVF file to read")
	private File file;
	
	@Parameter(label = "slice across thickness")
	private int slice;
	
	@Parameter(label = "X component image", type = ItemIO.OUTPUT)
	private Dataset Dx;
	
	@Parameter(label = "Y component image", type = ItemIO.OUTPUT)
	private Dataset Dy;
	
	@Parameter(label = "Z component image", type = ItemIO.OUTPUT)
	private Dataset Dz;
	

	public static void main(final String... args) throws Exception {
		// create the ImageJ application context with all available services
		final ImageJ ij = net.imagej.Main.launch(args);

		ij.command().run(writeOVF.class, true);
	}

	@Override
	public void run() {
		double[] steps= {0.1,0.1};
		long[] dims_data = {256,256,10};
		long[] dims_image = {256,256};
		String[] info={"test","unknow"};
		try {
			DataInputStream is= new DataInputStream(new BufferedInputStream(new FileInputStream(file.getAbsolutePath())));
			// DataOutputStream is big endian, as is OVF 1.0
			readHeader(is,dims_data,steps,info);
			log.info("size is "+dims_data[0]+" "+dims_data[1]+" "+dims_data[2]);
			dims_image[0]=dims_data[0];
			dims_image[1]=dims_data[1];
	        final AxisType[] axis = {Axes.X, Axes.Y};
			Dx=datasetService.create(new FloatType(), dims_image, "X", axis);
			Dy=datasetService.create(new FloatType(), dims_image, "Y", axis);
			Dz=datasetService.create(new FloatType(), dims_image, "Z", axis);
			final LinearAxis xAxes= new DefaultLinearAxis(Axes.X, info[1], steps[0]);
			final LinearAxis yAxes =new DefaultLinearAxis(Axes.Y, info[1], steps[1]);
			//xAxis.setScale(steps[0]);
			//yAxis.setScale(steps[1]);
			final CalibratedAxis[] caxis = { xAxes, yAxes };
			Dx.setAxes(caxis);
			Dy.setAxes(caxis);
			Dz.setAxes(caxis);
			readMeat(is,Dx,Dy,Dz,dims_data,slice);
			is.close();
			
		} catch (IOException e) {
			  log.error("IOError " + e.getMessage()+ " reading data from "+file.getAbsolutePath());
			  }
		
	}

	@Override
	public void cancel() {
		log.info("Cancelled");
	}

	@Override
	public void preview() {
		log.info("read a slice from an OVF 1.0 OOMMF file as three images corresponding to the XYZ components");
		statusService.showStatus(header);
	}

	protected void readHeader(DataInputStream is, long[] dims, double[] step, String[] info) throws IOException {
		String item;
		String definition=is.readLine();
		if (definition.contains("OOMMF: rectangular mesh v1.0")) 
		{
			log.info("Good, rectangular grid v1.0");
			item=is.readLine();
			while (!item.contains("End: Header")) {
				if (item.contains("xnodes")) {
					dims[0]=Integer.parseInt((item.split(" "))[2]);
					log.info("found xnodes: "+dims[0]);
				} else if (item.contains("ynodes")) {
					dims[1]=Integer.parseInt((item.split(" "))[2]);
					log.info("found ynodes: "+dims[1]);
				} else if (item.contains("znodes")) {
					dims[2]=Integer.parseInt((item.split(" "))[2]);
					log.info("found znodes: "+dims[2]);
				} else if (item.contains("xstepsize")) {
					step[0]=Double.parseDouble((item.split(" "))[2]);
					log.info("found xs: "+step[0]);
				} else if (item.contains("ystepsize")) {
					step[1]=Double.parseDouble((item.split(" "))[2]);
					log.info("found ys: "+step[1]);
				} else if (item.contains("meshtype: rectangular")) {
					log.info("good, rectangular meshtype, only one we know");
				} else if (item.contains("Title:")) {
					info[0]=item;
					log.info("name is: "+info[0]);
				} else if (item.contains("meshunit:")) {
					info[1]=item.split(" ")[2];
					log.info("units: "+info[1]);
				}
				item=is.readLine();
			}
			if (1E-6>step[0] || step[0]>1E-10 || info[1].equals("m")) {
				info[1]="nm";
				step[0]*=1e9;
				step[1]*=1e9;
			}
		}
		/* os.writeBytes("# Segment count: 1\n");
		os.writeBytes("# Begin: segment\n");
		os.writeBytes("# Begin: Header\n");
		os.writeBytes("# Title: Generated from ImageJ\n");
		os.writeBytes("# Desc: Name "+name+"\n");
		os.writeBytes("# Desc: Generated on " + new Date()+"\n");
		os.writeBytes("# meshtype: rectangular\n");
		os.writeBytes("# meshunit: nm\n");
		os.writeBytes("# xbase: 5\n");
		os.writeBytes("# ybase: 5\n");
		os.writeBytes("# zbase: 3\n");
		os.writeBytes("# xstepsize: "+xstepsize+"\n");
		os.writeBytes("# ystepsize: "+ystepsize+"\n");
		os.writeBytes("# zstepsize: "+zstepsize+"\n");
		os.writeBytes("# xnodes: "+nx+"\n");
		os.writeBytes("# ynodes: "+ny+"\n");
		os.writeBytes("# znodes: 1\n");
		os.writeBytes("# xmin: 0\n");
		os.writeBytes("# ymin: 0\n");
		os.writeBytes("# zmin: 0\n");
		os.writeBytes("# xmax: 10000\n");
		os.writeBytes("# ymax: 10000\n");
		os.writeBytes("# zmax: 6e-09\n");
		os.writeBytes("# valueunit: A/m\n");
		os.writeBytes("# valuemultiplier: 1\n");
		os.writeBytes("# ValueRangeMinMag: 1e-8\n");
		os.writeBytes("# ValueRangeMaxMag: 1.0\n");
		os.writeBytes("# End: Header\n");
		*/
	}
	/**
	 * Read image slices from input file. 
	 * 
	 */
	private void readMeat(DataInputStream is, final Dataset Dx, final Dataset Dy, final Dataset Dz, long[] dims_data, int slice) 
	throws IOException {
		
		String item;
		int x=(int)dims_data[0];
		int y=(int)dims_data[1];
		int z=(int)dims_data[2];
		double[][][] ovf_x=new double[x][y][z]; 
		double[][][] ovf_y=new double[x][y][z]; 
		double[][][] ovf_z=new double[x][y][z];
		
		final RandomAccess<? extends RealType> ra1 = Dx.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra2 = Dy.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra3 = Dz.getImgPlus().randomAccess();
		
			item=is.readLine();
			if (item.contains("8"))
			{	
				log.info("Good, data binary 8");
				Double check=is.readDouble();
				if (check!=123456789012345.0)
					{log.info("Wrong check number\n");}
				for (int k=0;k<z;k++) {
					for (int j=y-1;j>=0;j--) {
						for (int i=0;i<x;i++) {				
							ovf_x[i][j][k]=is.readDouble();
							ovf_y[i][j][k]=is.readDouble();
							ovf_z[i][j][k]=is.readDouble();
						}
					}
				}
			} else if (item.contains("4"))
			{
				log.info("Good, data binary 4");
				Float check=is.readFloat();
				if (check!=1234567.0)
					{log.info("Wrong check number\n");}
				for (int k=0;k<z;k++) {
					for (int j=y-1;j>=0;j--) {
						for (int i=0;i<x;i++) {				
							ovf_x[i][j][k]=is.readFloat();
							ovf_y[i][j][k]=is.readFloat();
							ovf_z[i][j][k]=is.readFloat();
						}
					}
				}
			} else if (item.contains("Begin: text"))
			{
				log.info("Bad, text data, abort");
			}
				
			final long[] pos = new long[2];
			for (int i=0;i<x;i++) {
				for (int j=0;j<y;j++) {
					pos[0]=(long)i;
					pos[1]=(long)j;
					ra1.setPosition(pos);
					ra2.setPosition(pos);
					ra3.setPosition(pos);
					ra1.get().setReal((double)ovf_x[i][j][slice]);
					ra2.get().setReal((double)ovf_y[i][j][slice]);
					ra3.get().setReal((double)ovf_z[i][j][slice]);
				}
			}
			/**os.writeDouble(123456789012345.0);
			final long[] pos = new long[x.numDimensions()];
			final long nx=x.dimension(0);
			final long ny=x.dimension(1);
			log.info("Size is "+nx+" "+ny);
			for(long j=ny;j>0;j--) {
				for (long i=0;i<nx;i++) {
					pos[0]=i;
					pos[1]=j-1;
					ra1.setPosition(pos);
					ra2.setPosition(pos);
					ra3.setPosition(pos);
					os.writeDouble(ra1.get().getRealDouble());
					os.writeDouble(ra2.get().getRealDouble());
					os.writeDouble(ra3.get().getRealDouble());
				}
			}
			os.writeBytes("\n");
			os.writeBytes("# End: data binary 8\n");
		**/
		

	}

	
}

