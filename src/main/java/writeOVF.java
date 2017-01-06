/*
 * writeOVF - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2016 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
 * 
 */

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;

import net.imagej.Dataset;
import net.imagej.DatasetService;
import net.imagej.ImageJ;
import net.imagej.ImgPlus;
import net.imagej.ops.OpService;
import net.imagej.ops.Ops;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RandomAccess;

import net.imglib2.img.Img;
import net.imglib2.meta.CalibratedSpace;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;
import org.scijava.command.Command;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import org.scijava.ItemVisibility;
import org.scijava.app.StatusService;
import org.scijava.command.Command;
import org.scijava.command.Previewable;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

//import net.imagej.*;

import io.scif.services.DatasetIOService;

@Plugin(type = Command.class, headless = true,
	menuPath = "Plugins>LEEMandPEEM>writeOVF")
public class writeOVF implements Command, Previewable {

	@Parameter
	private LogService log;

	@Parameter
	private StatusService statusService;

	@Parameter
	private DatasetService datasetService;

	//@Parameter
	//private DatasetIOService datasetIOService;

	@Parameter(visibility = ItemVisibility.MESSAGE)
	private final String header = "Write an OVF 1.0 OOMMF vector file from three images assumed to be the XYZ components";

	@Parameter(label = "X component image")
	private Dataset x;

	@Parameter(label = "Y component image")
	private Dataset y;
	
	@Parameter(label = "Z component image")
	private Dataset z;
	
	@Parameter(label = "X Step Size")
	private String xstepsize;
	
	@Parameter(label = "Y Step Size")
	private String ystepsize;
	
	@Parameter(label = "Z Step Size")
	private String zstepsize;
	
	@Parameter(label = "Text format")
	private Boolean format_text;
	
	@Parameter(label ="Binary8 format")
	private Boolean format_binary8;

	
	@Parameter(label="Output File")
	private String outputfile;

	public static void main(final String... args) throws Exception {
		// create the ImageJ application context with all available services
		final ImageJ ij = net.imagej.Main.launch(args);

		ij.command().run(writeOVF.class, true);
	}

	@Override
	public void run() {
	
		
		if (x.numDimensions() != y.numDimensions() | y.numDimensions() != z.numDimensions()) {
			log.error("Input datasets must have the same number of dimensions.");
			return;
		}
		if (x.numDimensions()>2) {
			log.error("Only 2D images, please");
		}
		
		//double xstepsize=x.calibration(0);
		//double ystepsize=x.getImgPlus().calibration(1);
		//double zstepsize=xstepsize;
		
		try {
			DataOutputStream os= new DataOutputStream(new BufferedOutputStream(new FileOutputStream(outputfile)));
			// DataOutputStream is big endian, as is OVF 1.0
			writeHeader(os,x.dimension(0),x.dimension(1),x.getName()+" "+y.getName()+" "+z.getName());
			writeMeat(os,x,y,z);
			writeFooter(os);
			os.close();
		} catch (IOException e) {
			  log.error("IOError " + e.getMessage()+ " saving data to "+outputfile);
			  }
		
	}

	@Override
	public void cancel() {
		log.info("Cancelled");
	}

	@Override
	public void preview() {
		log.info("save three datasets as an OVF 1.0 OOMMF file");
		statusService.showStatus(header);
	}

	protected void writeHeader(DataOutputStream os, long nx, long ny, String name) throws IOException {
		os.writeBytes("# OOMMF: rectangular mesh v1.0\n");
		os.writeBytes("# Segment count: 1\n");
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
		
	}
	protected void writeFooter(DataOutputStream os) throws IOException {
		os.writeBytes("# End: segment\n");

	}
	/**
	 * Write image planes in output file. 
	 * 
	 */
	private void writeMeat(DataOutputStream os, final Dataset x, final Dataset y, final Dataset z) 
	throws IOException {
		
	
		final RandomAccess<? extends RealType> ra1 = x.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra2 = y.getImgPlus().randomAccess();
		final RandomAccess<? extends RealType> ra3 = z.getImgPlus().randomAccess();
		
		if (format_text) {
			os.writeBytes("# Begin: data text\n");
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
					os.writeBytes(Float.toString(ra1.get().getRealFloat())+" ");
					os.writeBytes(Float.toString(ra2.get().getRealFloat())+" ");
					os.writeBytes(Float.toString(ra3.get().getRealFloat())+" ");
				}
			}
			os.writeBytes("\n");
			os.writeBytes("# End: data text\n");
			
		} else if (format_binary8) {
			os.writeBytes("# Begin: data binary 8\n");
			os.writeDouble(123456789012345.0);
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
		} else {
			os.writeBytes("# Begin: data binary 4\n");
			os.writeFloat(1234567.0f);
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
					os.writeFloat(ra1.get().getRealFloat());
					os.writeFloat(ra2.get().getRealFloat());
					os.writeFloat(ra3.get().getRealFloat());
				}
			}
			os.writeBytes("\n");
			os.writeBytes("# End: data binary 4\n");
		}

	}
	
}

