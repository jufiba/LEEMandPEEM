/**
 * Scifio-UView plugin. This plugin reads single images from the UKSOFT2000 format. This format is used by the
 * Elmitec camera acquisition program for their LEEM/PEEM line of instruments.
 *
 * It is a simple unsigned 16bit binary dump preceded by a header with some experimental parameters and the size.
 * For the record, it is the same format originally used in a Transputer electronics control unit for Scanning Tunneling
 * Microscopy, from Uwe Knipping (who then moved to Elmitec).
 *
 * @author Juan de la Figuera
 */


import io.scif.AbstractChecker;
import io.scif.AbstractFormat;
import io.scif.AbstractMetadata;
import io.scif.AbstractParser;
import io.scif.ByteArrayPlane;
import io.scif.ByteArrayReader;
import io.scif.Field;
import io.scif.Format;
import io.scif.FormatException;
import io.scif.HasColorTable;
import io.scif.ImageMetadata;
import io.scif.MetadataLevel;
import io.scif.SCIFIO;
import io.scif.config.SCIFIOConfig;
import io.scif.util.FormatTools;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import net.imagej.axis.Axes;
import net.imglib2.Interval;

import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;
import org.scijava.plugin.Plugin;



public class UView_reader {

	@Plugin(type = Format.class)

	public static class UKFormat extends AbstractFormat {

		public static final String UVIEW_MAGIC_STRING = "UKSOFT2001";

		@Override
		public String getFormatName() {
			return "UKSOFT2000/UView";
		}

		@Override
		protected String[] makeSuffixArray() {
			return new String[] { "dat" };
		}

		public static class Metadata extends AbstractMetadata {

			@Field(label="StartVoltage")
			public double startvoltage=0.0;
			@Field(label="Temperature")
			private double temperature=25.0;
			@Field(label="Azimuth")
			private double azimuth=360.0;
			@Field(label="Pressure")
			private double pressure=0.0;
			@Field(label="time")
			private long time=0L;
			@Field(label="Date")
			private String date="1/1/1 00:00 +0200";
			@Field(label="micrometer_x")
			private double micrometer_x=0.0;
			@Field(label="micrometer_y")
			private double micrometer_y=0.0;

			@Field(label="offset")
			private int offset;

			/** Absolute file offset of each image's pixel data. */
			private long[] dataOffsets = new long[0];

			public long[] getDataOffsets() {
				return dataOffsets;
			}

			public void setDataOffsets(long[] dataOffsets) {
				this.dataOffsets=dataOffsets;
			}

			public double getStartVoltage() {
				return startvoltage;
			}
			public void setStartVoltage(double startvoltage) {
				this.startvoltage=startvoltage;
			}

			public double getTemperature() {
				return temperature;
			}
			public void setTemperature(double temperature) {
				this.temperature=temperature;
			}

			public double getAzimuth() {
				return azimuth;
			}
			public void setAzimuth(double azimuth) {
				this.azimuth=azimuth;
			}

			public double getPressure() {
				return pressure;
			}
			public void setPressure(double pressure) {
				this.pressure=pressure;
			}

			public double getMicrometerX() {
				return micrometer_x;
			}
			public void setMicrometerX(double micrometer_x) {
				this.micrometer_x=micrometer_x;
			}

			public double getMicrometerY() {
				return micrometer_y;
			}
			public void setMicrometerY(double micrometer_y) {
				this.micrometer_y=micrometer_y;
			}

			public int getOffset() {
				return offset;
			}

			public void setOffset(int offset) {
				this.offset=offset;
			}

			@Override
			public void populateImageMetadata() {

				final ImageMetadata iMeta = get(0);

				iMeta.setOrderCertain(true);
				iMeta.setFalseColor(false);
				iMeta.setThumbnail(false);
				iMeta.setPixelType(FormatTools.UINT16);
				iMeta.setLittleEndian(true);

				// Copy format-level table into image-level table so Fiji's
				// Show Info and getProperty() can access the fields.
				iMeta.getTable().putAll(getTable());
			}
		}

		public static class Parser extends AbstractParser<Metadata> {

			/**
			 * Reads a NUL-terminated string. SCIFIO 0.45's
			 * {@code DataHandle.readCString()} returns the terminating NUL as part
			 * of the string (0.28 did not), which both corrupts the value and makes
			 * {@code length()} one more than the bytes the caller should account for.
			 */
			private static String readCStr(final DataHandle<Location> stream)
					throws IOException
			{
				final String s = stream.readCString();
				return (s != null && s.length() > 0 && s.charAt(s.length() - 1) == '\0')
						? s.substring(0, s.length() - 1) : s;
			}

			@Override
			protected void typedParse(final DataHandle<Location> stream,
					final Metadata meta, final SCIFIOConfig config) throws IOException,
			FormatException
			{
				meta.createImageMetadata(1);
				final ImageMetadata iMeta = meta.get(0);
				stream.setOrder(DataHandle.ByteOrder.LITTLE_ENDIAN);
				long filelength=stream.length();
				// Minimum information required to read the file
				stream.seek(40);
				int UKFH_width = stream.readUnsignedShort();
				int UKFH_height= stream.readUnsignedShort();
				int UKFH_nimages = Math.max(1, stream.readUnsignedShort());
				iMeta.addAxis(Axes.X, UKFH_width);
				iMeta.addAxis(Axes.Y, UKFH_height);
				// File header, starts with magic string. Everything up to and
				// including the image walk is needed to locate pixel data, so it
				// must run at every MetadataLevel — openPlane depends on it.
				stream.seek(20);
				int UKFH_size = stream.readUnsignedShort();
				int UKFH_version = stream.readUnsignedShort();
				int UKFH_bitsperpixel= stream.readUnsignedShort();
				if (UKFH_version>7) {
					int UKFH_camerabitsperpixel=stream.readUnsignedShort();
					int UKFH_MCPdiameterinpixels=stream.readUnsignedShort();
					int UKFH_hbinning=stream.readUnsignedByte();
					int UKFH_vbinning=stream.readUnsignedByte();
				}
				// attachedRecipeSize is always at absolute offset 46 in the file header
				// (per spec: file header is 104 bytes fixed, attachedRecipeSize at offset 46)
				int UKFH_attachedrecipesize;
				if (UKFH_version>6) {
					stream.seek(46);
					UKFH_attachedrecipesize=stream.readUnsignedShort();
				} else {
					UKFH_attachedrecipesize=0;
				}
				// The recipe block on disk is always 128 bytes when present (attachedRecipeSize > 0)
				int recipeBlockSize = (UKFH_attachedrecipesize > 0) ? 128 : 0;

				{
					// A .dat may hold NrImages images, each with its own image header,
					// markup block, LEEM data block and pixel data. Walk them to get
					// the data offset of every plane.
					final long frameBytes = 2L*UKFH_width*UKFH_height;
					final long[] offsets = new long[UKFH_nimages];
					long walk = UKFH_size + recipeBlockSize;
					for (int n=0; n<UKFH_nimages; n++) {
						stream.seek(walk);
						int ihSize = stream.readUnsignedShort();
						stream.seek(walk + 22);
						int mk = stream.readUnsignedShort();
						int mkSize = (mk > 0) ? 128*((mk/128)+1) : 0;
						stream.seek(walk + 26);
						int ldv = stream.readUnsignedShort();
						offsets[n] = walk + ihSize + mkSize + (ldv > 2 ? ldv : 0);
						if (offsets[n] + frameBytes > filelength)
							throw new FormatException("image " + (n+1) + " of " + UKFH_nimages
									+ " runs past end of file (offset " + offsets[n] + ")");
						walk = offsets[n] + frameBytes;
					}
					meta.setDataOffsets(offsets);
					meta.setOffset((int)offsets[0]);
					if (UKFH_nimages > 1) {
						// One plane per image. Metadata below is the FIRST image's
						// LEEM block; SCIFIO's table is per image index, so per-plane
						// tags are not represented here (use UView_Folder_Reader for
						// per-frame metadata as slice labels).
						iMeta.addAxis(Axes.TIME, UKFH_nimages);
					}

				}

				final MetadataLevel level = config.parserGetLevel();
				if (level != MetadataLevel.MINIMUM) {
					stream.seek(UKFH_size + recipeBlockSize);
					// Image header
					int UKIH_size= stream.readUnsignedShort();
					int UKIH_version= stream.readUnsignedShort();
					int UKIH_colorlow= stream.readUnsignedShort();
					int UKIH_colorhigh= stream.readUnsignedShort();
					long UKIH_time= stream.readLong();
					DateFormat formatter = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss Z");
					String UKIH_date=formatter.format(new Date((UKIH_time - 116444736000000000L)/10000L ));
					meta.getTable().put("Date", UKIH_date);
					int UKIH_maskx = stream.readUnsignedShort();
					int UKIH_masky = stream.readUnsignedShort();
					stream.seek(stream.offset()+2); // skip RotateMask (2 bytes)
					int UKIH_attachedmarkedsize=stream.readUnsignedShort();
					int MARKUP_size= (UKIH_attachedmarkedsize > 0) ? 128*((UKIH_attachedmarkedsize/128)+1) : 0;
					int UKIH_spin = stream.readUnsignedShort();
					int UKIH_leemdataversion= stream.readUnsignedShort();

					// When LEEMdataVersion > 2 its value IS the size of the external LEEM data block,
					// located after the markup block. Versions 1 and 2 embed the LEEM data inside
					// the image header starting at byte 28; the block runs to the end of the header.
					if (UKIH_leemdataversion >= 1) {
						int leemBlockSize;
						if (UKIH_leemdataversion > 2) {
							// Spec: LEEMdataVersion > 2 -> its value IS the size of an external
							// LEEM data block placed after the IMAGE MARKUP block.
							stream.seek(UKFH_size + recipeBlockSize + UKIH_size + MARKUP_size);
							leemBlockSize = UKIH_leemdataversion;
						} else {
							// Versions 1 and 2: the data lives in the image header's
							// LEEMdata array at offset 28. Its size depends on the IMAGE
							// HEADER version, not on UKIH_size:
							//   version  <= 5: LEEMdata[256], then a 4-byte spare
							//   version   > 5: LEEMdata[239], then applied_processing,
							//                  gray adjust zone, backgroundvalue,
							//                  desired_rendering and the rendering args
							stream.seek(UKFH_size + recipeBlockSize + 28);
							leemBlockSize = Math.min(UKIH_version > 5 ? 239 : 256,
									UKIH_size - 28);
						}
						int i=0;
						int rawTag;
						int tag;
						while (i < leemBlockSize) {
							rawTag=stream.readUnsignedByte();
							i++;
							// Spec: 0xFF means SKIP this byte, it is NOT an end-of-block marker.
							// The block length is the terminator.
							if (rawTag==0xFF) continue;
							tag = rawTag & 0x7F; // strip "hidden" bit (0x80 = recorded but not shown on image)
							switch (tag) {
								case 100:
									float UKLD_micrometerx=stream.readFloat();
									float UKLD_micrometery=stream.readFloat();
									meta.setMicrometerX(UKLD_micrometerx);
									meta.setMicrometerY(UKLD_micrometery);
									meta.getTable().put("MicrometerX", UKLD_micrometerx);
									meta.getTable().put("MicrometerY", UKLD_micrometery);
									i+=8;
									break;
								case 101:
									String UKLD_fov=readCStr(stream);
									meta.getTable().put("FOV", UKLD_fov);
									i+=UKLD_fov.length()+1;
									break;
								case 102:
									float UKLD_varian0=stream.readFloat();
									meta.getTable().put("Varian1", UKLD_varian0);
									i+=4;
									break;
								case 103:
									float UKLD_varian1=stream.readFloat();
									meta.getTable().put("Varian2", UKLD_varian1);
									i+=4;
									break;
								case 104:
									float UKLD_camera_exposure=stream.readFloat();
									meta.getTable().put("CameraExposure", UKLD_camera_exposure);
									i+=4;
									if (UKIH_leemdataversion>1) {
										// B1/B2: B1>0 averaging on, B2 = number of images (2..127);
										// B1==0 averaging off; B1<0 (0xFF) sliding average.
										int UKLD_b1=stream.readUnsignedByte();
										int UKLD_b2=stream.readUnsignedByte();
										i+=2;
										String avg;
										if (UKLD_b1==0) avg="off";
										else if (UKLD_b1>127) avg="sliding";
										else avg=Integer.toString(UKLD_b2);
										meta.getTable().put("Averaging", avg);
									}
									break;
								case 105:
									String UKLD_title=readCStr(stream);
									if (!UKLD_title.trim().isEmpty())
										meta.getTable().put("Title", UKLD_title);
									i+=UKLD_title.length()+1;
									break;
								// Varian gauges #1-#4, plus the additional gauges #5.. at 120-130.
								// All share the same layout: name, units, float.
								case 106: case 107: case 108: case 109:
								case 120: case 121: case 122: case 123: case 124: case 125:
								case 126: case 127: case 128: case 129: case 130: {
									String UKLD_gaugename=readCStr(stream);
									String UKLD_gaugeunits=readCStr(stream);
									float UKLD_gaugevalue=stream.readFloat();
									meta.getTable().put(UKLD_gaugename + " (" + UKLD_gaugeunits + ")", UKLD_gaugevalue);
									i+=UKLD_gaugename.length()+1+UKLD_gaugeunits.length()+1+4;
									break;
								}
								case 110:
									// Spec: "FOV, camera to FOV cal. factor": the string is the
									// FOV name, the float is the calibration factor.
									String UKLD_fovname=readCStr(stream);
									float UKLD_fovcalfactor=stream.readFloat();
									meta.getTable().put("FOVName", UKLD_fovname);
									meta.getTable().put("FOVCalFactor", UKLD_fovcalfactor);
									i+=UKLD_fovname.length()+1+4;
									break;
								case 111:
									float UKLD_phi=stream.readFloat();
									float UKLD_theta=stream.readFloat();
									meta.getTable().put("Phi", UKLD_phi);
									meta.getTable().put("Theta", UKLD_theta);
									i+=8;
									break;
								case 112:
									// Spin. Payload size is not documented; 2 bytes
									// determined empirically (spec declares spin a short).
									int UKLD_spin=stream.readShort();
									meta.getTable().put("Spin", UKLD_spin);
									i+=2;
									break;
								case 113:
									// FOV rotation (from LEEM presets). Payload size is not
									// documented; 4 bytes determined empirically.
									float UKLD_fovrotation=stream.readFloat();
									meta.getTable().put("FOVRotation", UKLD_fovrotation);
									i+=4;
									break;
								case 114:
									// Mirror state. Payload size is not documented;
									// 2 bytes determined empirically.
									int UKLD_mirrorstate=stream.readShort();
									meta.getTable().put("MirrorState", UKLD_mirrorstate);
									i+=2;
									break;
								case 115:
									float UKLD_MCPscreenvoltage=stream.readFloat();
									meta.getTable().put("MCPScreenVoltage", UKLD_MCPscreenvoltage);
									i+=4;
									break;
								case 116:
									float UKLD_MCPchannelplate=stream.readFloat();
									meta.getTable().put("MCPChannelPlate", UKLD_MCPchannelplate);
									i+=4;
									break;
								default:
									if (tag<100) {
										// LEEM2000 module reading.
										// Format: name + unit_digit(0-9) + 0x00 + float(4)
										// Unit codes: 0=none,1=V,2=mA,3=A,4=C,5=K,6=mV,7=pA,8=nA,9=uA
										String nameAndUnit=readCStr(stream);
										float module_reading=stream.readFloat();
										if (nameAndUnit.length() > 0) {
											char unitCode=nameAndUnit.charAt(nameAndUnit.length()-1);
											String modName=nameAndUnit.substring(0, nameAndUnit.length()-1);
											String[] unitNames={"","V","mA","A","°C","K","mV","pA","nA","µA"};
											String unit=(unitCode>='0' && unitCode<='9') ? unitNames[unitCode-'0'] : "";
											String key=unit.isEmpty() ? modName : modName+" ("+unit+")";
											meta.getTable().put(key, module_reading);
										}
										i+=nameAndUnit.length()+1+4;
										break;
									}
									// Unknown tag >= 100: its payload size is unknown, so the
									// byte stream can no longer be interpreted. Stop rather than
									// resynchronise on garbage (e.g. tag 112, spin, whose size
									// the format spec does not give).
									meta.getTable().put("UnreadLEEMTag",
											"tag " + tag + " at block offset " + (i-1));
									i = leemBlockSize;
									break;
								}
						}
					}
				}
			}
		}

		public static class Checker extends AbstractChecker {

			@Override
			public boolean suffixSufficient() {
				return true;
			}

			@Override
			public boolean suffixNecessary() {
				return true;
			}

			@Override
			public boolean isFormat(final DataHandle<Location> in)
					throws IOException
			{
				final int blockLen = UVIEW_MAGIC_STRING.length();
				if (!FormatTools.validStream(in, blockLen, false)) return false;
				return in.readString(blockLen).startsWith(UVIEW_MAGIC_STRING);
			}
		}

		public static class Reader extends ByteArrayReader<Metadata> {
			@Override
			public ByteArrayPlane openPlane(int imageIndex, long planeIndex,
					ByteArrayPlane plane, Interval bounds,
					SCIFIOConfig config) throws FormatException, IOException
			{
				final Metadata meta = getMetadata();
				final byte[] buf = plane.getBytes();

				FormatTools.checkPlaneForReading(meta, imageIndex, planeIndex,
						buf.length, bounds);

				int width=(int)meta.get(imageIndex).getAxisLength(Axes.X);
				int height=(int)meta.get(imageIndex).getAxisLength(Axes.Y);
				final long[] offsets = meta.getDataOffsets();
				getHandle().seek(planeIndex < offsets.length
						? offsets[(int)planeIndex] : meta.getOffset());
				for(int i=0;i<height;i++) {
					getHandle().readFully(buf,(height-1-i)*width*2,width*2); // Need to flip vertically
				}
				if (meta instanceof HasColorTable) {
					plane.setColorTable(((HasColorTable) meta).getColorTable(imageIndex,
							planeIndex));
				}

				return plane;
			}

			@Override
			protected String[] createDomainArray() {
				String[] domains={FormatTools.EM_DOMAIN};
				return (domains);
			}
		}

		// This method is provided simply to confirm the format is discovered
		public static void main(final String... args) throws FormatException {

			final SCIFIO scifio = new SCIFIO();
			final Location sampleImage = new FileLocation("notAnImage.dat");
			final Format format = scifio.format().getFormat(sampleImage);
			System.out.println("UKSoft found via FormatService: " +
					(format != null));

			scifio.getContext().dispose();
		}

	}

}
