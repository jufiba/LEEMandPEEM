# LEEMandPEEM

Fiji/ImageJ2 plugins for low-energy electron microscopy (LEEM) and x-ray photoemission electron microscopy (PEEM), in particular for instruments from [Elmitec](http://www.elmitec.de) running the U-view 2002 software.

## Plugins

### UView reader (SCIFIO format)

Registers the Elmitec UKSOFT2000 format (`.dat` extension) with SCIFIO, allowing files to be opened directly via **File > Open** or drag-and-drop in Fiji. Single images only; multi-image files are not supported.

Metadata stored in the file header (start voltage, temperature, pressure, field of view, micrometer position, date, etc.) is attached to the dataset and visible via **Image > Show Info**.

### UView Folder Reader

**Plugins > LEEMandPEEM > UView Folder Reader**

Opens a folder of `.dat` files as an ImageJ stack. A dialog allows filtering by filename substring and selecting a range and increment. Each slice label contains the metadata extracted from that file's header in `key=value` format, which can be used by **Plot Intensity vs Tag**.

### Plot Intensity vs Tag

**Plugins > LEEMandPEEM > Plot Intensity vs Tag**

Plots the mean intensity of each slice in a stack against a metadata value embedded in the slice labels. Typical use: plot intensity vs. start voltage to obtain an IV curve.

Features:
- **Tag dropdown** — lists all numeric metadata fields found in the slice labels; includes *Frame Number* as a fallback
- **Formula** — optional arithmetic expression applied to the tag values before plotting, with `x` as the variable (e.g. `350 - x`, `x * 0.001`, `(x - 5) / 2`)
- **X axis label** — pre-filled with the unit extracted from the tag name (editable)
- Respects an active ROI; if none is drawn the whole frame is used

## Installation

Copy `LEEMandPEEM-<version>.jar` from `target/` into the `plugins/` folder of your Fiji installation and restart Fiji.

## Building from source

Requires Maven and Java 11+.

```bash
mvn clean package
```

The jar will be in `target/`.

## Requirements

- [Fiji](https://fiji.sc) with SCIFIO 0.45 or later (included in current Fiji releases)
- pom-scijava 34.1.0

## Related plugins

Magnetic vector field analysis (OVF format I/O, XYZ reconstruction, spherical conversion) has moved to [MagneticHelper](https://github.com/Jufiba/MagneticHelper).

## License

GNU General Public License v2.0 — see [LICENSE.txt](LICENSE.txt).

## Author

Juan de la Figuera, IQFR-CSIC (<http://surfmoss.iqfr.csic.es>)
