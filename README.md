# LEEMandPEEM

Fiji/ImageJ2 plugins for low-energy electron microscopy (LEEM) and x-ray photoemission electron microscopy (PEEM), in particular for instruments from [Elmitec](http://www.elmitec.de) running the U-view 2002 software.

## Plugins

### UView reader (SCIFIO format)

Registers the Elmitec UKSOFT2000 format (`.dat` extension) with SCIFIO, allowing files to be opened directly via **File > Open** or drag-and-drop in Fiji. Single images only; multi-image files are not supported.

Metadata stored in the file header (start voltage, temperature, pressure, field of view, micrometer position, date, etc.) is attached to the dataset and visible via **Image > Show Info**.

### UView Folder Reader

**Plugins > LEEMandPEEM > UView Folder Reader**

Opens a folder of `.dat` files as an ImageJ stack. A dialog allows filtering by filename substring and selecting a range and increment (options are remembered between runs). Each slice label contains the metadata extracted from that file's header in `key=value` format, which can be used by **Plot Intensity vs Tag**.

**Companion CSV support:** if the folder contains a CSV file whose name does not include the word `meta`, the plugin reads it and appends its `Energy` and `M4b` columns as additional slice label tags (`Energy (eV)` and `M4b`). This follows the metadata CSV format produced by the PEEM data acquisition system at the [Solaris](https://www.synchrotron.uj.edu.pl) DEMETER beamline. Both comma- and semicolon-delimited files are supported; UTF-8 BOM (added by Excel on Windows) is handled automatically.

### Plot Intensity vs Tag

**Plugins > LEEMandPEEM > Plot Intensity vs Tag**

Plots the mean intensity of each slice in a stack against a metadata value embedded in the slice labels. Typical use: plot intensity vs. start voltage (IV curve) or photon energy (XAS/NEXAFS spectrum).

Features:
- **X tag dropdown** — lists all numeric metadata fields found in the slice labels; includes *Frame Number* as a fallback. X axis label is pre-filled from the tag name (editable, e.g. rename to *Binding Energy (eV)* for XPS)
- **X formula** — optional expression applied to the tag values before plotting (`x` = tag value; e.g. `350 - x`, `x * 0.001`)
- **Y tag + Y formula** — optional second tag and formula for transforming the intensity (`y` = mean intensity, `t` = Y tag value). Typical use: `y / t` with *M4b* as the Y tag to normalise by beamline flux
- **Multi-ROI plotting** — if the ROI Manager is open, one curve is plotted per ROI with a legend; selected ROIs in the manager are used, otherwise all. Without the ROI Manager the active ROI (or whole frame) is used
- **Save CSV** — checkbox to export the plot data (X column + one column per ROI) to a CSV file, ready for further analysis in Python or other tools
- All dialog choices are remembered between runs via `ij.Prefs`

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
