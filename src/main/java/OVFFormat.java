/*
 * OVFFormat - This file is part of the LEEMandPEEM plugins
 * Copyright (C) 2016 - Juan de la Figuera
 *
 * This file is hereby licensed under the terms of the GNU GPL v2.0
 * For more information, see the LICENSE file which you should have received
 * along with this program.
 *
 * SCIFIO Format plugin for OOMMF OVF 1.0 vector field files.
 * Registering this class as a SCIFIO Format allows Fiji to open .ovf files
 * directly through File > Open (or drag-and-drop), without using the
 * readOVF command plugin.
 *
 * The resulting dataset has axes [X, Y, Z, CHANNEL] where CHANNEL 0/1/2
 * correspond to the Mx, My, Mz vector components respectively.
 * Only binary-4 and binary-8 encodings are supported (not text).
 */

import java.io.IOException;

import io.scif.AbstractChecker;
import io.scif.AbstractFormat;
import io.scif.AbstractMetadata;
import io.scif.AbstractParser;
import io.scif.ByteArrayPlane;
import io.scif.ByteArrayReader;
import io.scif.Format;
import io.scif.FormatException;
import io.scif.ImageMetadata;
import io.scif.config.SCIFIOConfig;
import io.scif.io.RandomAccessInputStream;
import io.scif.util.FormatTools;

import net.imagej.axis.Axes;
import net.imagej.axis.DefaultLinearAxis;
import org.scijava.plugin.Plugin;

@Plugin(type = Format.class)
public class OVFFormat extends AbstractFormat {

	@Override
	public String getFormatName() {
		return "OOMMF OVF 1.0";
	}

	@Override
	protected String[] makeSuffixArray() {
		return new String[]{"ovf"};
	}

	// =========================================================
	// Metadata
	// =========================================================

	public static class Metadata extends AbstractMetadata {

		private long nx = 1, ny = 1, nz = 1;
		private double xStep = 1.0, yStep = 1.0, zStep = 1.0;
		private String meshUnit = "m";
		private String dataEncoding = "binary 8";
		private long dataOffset = 0;

		public long   getNx()           { return nx; }
		public long   getNy()           { return ny; }
		public long   getNz()           { return nz; }
		public String getMeshUnit()     { return meshUnit; }
		public String getDataEncoding() { return dataEncoding; }
		public long   getDataOffset()   { return dataOffset; }

		void setNx(long v)           { nx = v; }
		void setNy(long v)           { ny = v; }
		void setNz(long v)           { nz = v; }
		void setXStep(double v)      { xStep = v; }
		void setYStep(double v)      { yStep = v; }
		void setZStep(double v)      { zStep = v; }
		void setMeshUnit(String v)   { meshUnit = v; }
		void setDataEncoding(String v) { dataEncoding = v; }
		void setDataOffset(long v)   { dataOffset = v; }

		@Override
		public void populateImageMetadata() {
			createImageMetadata(1);
			final ImageMetadata iMeta = get(0);
			// Axes: X, Y, Z (spatial), then CHANNEL (3 vector components).
			// setPlanarAxisCount(2) tells SCIFIO that X and Y form each plane;
			// Z and CHANNEL become the plane-index dimensions.
			iMeta.addAxis(new DefaultLinearAxis(Axes.X,       meshUnit, xStep), nx);
			iMeta.addAxis(new DefaultLinearAxis(Axes.Y,       meshUnit, yStep), ny);
			iMeta.addAxis(new DefaultLinearAxis(Axes.Z,       meshUnit, zStep), nz);
			iMeta.addAxis(new DefaultLinearAxis(Axes.CHANNEL),                  3);
			iMeta.setPixelType(FormatTools.FLOAT);
			iMeta.setLittleEndian(false);   // OVF binary data is big-endian
			iMeta.setIndexed(false);
			iMeta.setFalseColor(false);
			iMeta.setPlanarAxisCount(2);
		}
	}

	// =========================================================
	// Checker
	// =========================================================

	public static class Checker extends AbstractChecker {

		@Override
		public boolean isFormat(final RandomAccessInputStream stream) throws IOException {
			stream.mark(128);
			final String line = stream.readLine();
			stream.reset();
			return line != null && line.toLowerCase().contains("oommf: rectangular mesh v1.0");
		}
	}

	// =========================================================
	// Parser
	// =========================================================

	public static class Parser extends AbstractParser<Metadata> {

		@Override
		protected void typedParse(final RandomAccessInputStream stream,
				final Metadata meta, final SCIFIOConfig config)
				throws IOException, FormatException {

			String line = stream.readLine();
			if (line == null || !line.toLowerCase().contains("oommf: rectangular mesh v1.0")) {
				throw new FormatException("Not an OOMMF OVF 1.0 rectangular mesh file");
			}

			// --- Parse header fields ---
			line = stream.readLine();
			while (line != null && !line.toLowerCase().contains("end: header")) {
				final String[] tokens = line.trim().split("\\s+");
				final String lower = line.toLowerCase();
				if (tokens.length >= 3) {
					if      (lower.contains("xnodes:"))    meta.setNx(Long.parseLong(tokens[2]));
					else if (lower.contains("ynodes:"))    meta.setNy(Long.parseLong(tokens[2]));
					else if (lower.contains("znodes:"))    meta.setNz(Long.parseLong(tokens[2]));
					else if (lower.contains("xstepsize:")) meta.setXStep(Double.parseDouble(tokens[2]));
					else if (lower.contains("ystepsize:")) meta.setYStep(Double.parseDouble(tokens[2]));
					else if (lower.contains("zstepsize:")) meta.setZStep(Double.parseDouble(tokens[2]));
					else if (lower.contains("meshunit:"))  meta.setMeshUnit(tokens[2]);
				}
				line = stream.readLine();
			}

			// --- Find the "Begin: data ..." line ---
			line = stream.readLine();
			while (line != null && !line.toLowerCase().contains("begin: data")) {
				line = stream.readLine();
			}
			if (line == null) {
				throw new FormatException("No data section found in OVF file");
			}

			final String lower = line.toLowerCase();
			if (lower.contains("8")) {
				meta.setDataEncoding("binary 8");
			} else if (lower.contains("4")) {
				meta.setDataEncoding("binary 4");
			} else {
				throw new FormatException(
					"OVF text format is not supported; only binary 4 and binary 8 are handled");
			}

			// Record byte offset of the first data byte (right after the "Begin: data" line).
			// AbstractParser will call meta.populateImageMetadata() after this method returns.
			meta.setDataOffset(stream.getFilePointer());
		}
	}

	// =========================================================
	// Reader
	// =========================================================

	public static class Reader extends ByteArrayReader<Metadata> {

		@Override
		protected String[] createDomainArray() {
			return new String[]{FormatTools.UNKNOWN_DOMAIN};
		}

		/**
		 * Reads one 2-D plane (XY slice) from the OVF file.
		 *
		 * SCIFIO plane ordering for axes [X, Y, Z(nz), CHANNEL(3)]:
		 *   planeIndex = z + nz * channel   (Z varies fastest)
		 *
		 * The OVF binary layout for each z-slice is:
		 *   for j = ny-1 downto 0:
		 *     for i = 0 to nx-1:
		 *       vx, vy, vz   (interleaved, big-endian)
		 * Y is therefore stored in reverse order and must be un-reversed on read.
		 */
		@Override
		public ByteArrayPlane openPlane(final int imageIndex, final long planeIndex,
				final ByteArrayPlane plane, final long[] planeMin, final long[] planeMax,
				final SCIFIOConfig config) throws FormatException, IOException {

			final Metadata meta = getMetadata();
			final int nx  = (int) meta.getNx();
			final int ny  = (int) meta.getNy();
			final int nz  = (int) meta.getNz();
			final String enc = meta.getDataEncoding();

			// Decode which z-slice and vector component this plane represents.
			final int z       = (int)(planeIndex % nz);
			final int channel = (int)(planeIndex / nz);

			// Sub-region within the XY plane requested by the caller.
			// planeMin = {xOffset, yOffset}, planeMax = {xLength, yLength}
			final int xStart = (int) planeMin[0];
			final int yStart = (int) planeMin[1];
			final int width  = (int) planeMax[0];
			final int height = (int) planeMax[1];

			final byte[] buf = plane.getBytes();
			final RandomAccessInputStream stream = getStream();

			final int bytesPerValue = enc.equals("binary 8") ? 8 : 4;

			// Seek to the beginning of the requested z-slice, skipping:
			//   - the leading checksum value (bytesPerValue bytes)
			//   - all preceding z-slices
			final long sliceOffset = meta.getDataOffset()
					+ bytesPerValue
					+ (long) z * ny * nx * 3L * bytesPerValue;
			stream.seek(sliceOffset);

			// Read the slice row by row, un-reversing the Y axis.
			for (int jFile = 0; jFile < ny; jFile++) {
				final int yOut = ny - 1 - jFile;
				final boolean rowInRegion = (yOut >= yStart && yOut < yStart + height);

				for (int i = 0; i < nx; i++) {
					final float vx, vy, vz;
					if (enc.equals("binary 8")) {
						vx = (float) stream.readDouble();
						vy = (float) stream.readDouble();
						vz = (float) stream.readDouble();
					} else {
						vx = stream.readFloat();
						vy = stream.readFloat();
						vz = stream.readFloat();
					}

					if (!rowInRegion || i < xStart || i >= xStart + width) continue;

					final float val  = channel == 0 ? vx : (channel == 1 ? vy : vz);
					final int   bits = Float.floatToIntBits(val);
					final int outX   = i - xStart;
					final int outY   = yOut - yStart;
					final int idx    = (outY * width + outX) * 4;
					// Write as big-endian float (matches iMeta.setLittleEndian(false))
					buf[idx]     = (byte)(bits >> 24);
					buf[idx + 1] = (byte)(bits >> 16);
					buf[idx + 2] = (byte)(bits >> 8);
					buf[idx + 3] = (byte) bits;
				}
			}

			return plane;
		}
	}
}
