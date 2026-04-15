# LEEMandPEEM

Fiji/ImageJ2 plugins for low-energy electron microscopy (LEEM) and x-ray photoemission electron microscopy (PEEM), in particular for instruments from [Elmitec](http://www.elmitec.de) running the U-view 2002 software.

## Plugins

### UView reader (SCIFIO format)

Registers the Elmitec UKSOFT2000 format (`.dat` extension) with SCIFIO, allowing files to be opened directly via **File > Open** or drag-and-drop in Fiji. Single images only; multi-image files are not supported. However, it is not too fast, due to the overhead of reading all the fileds. For long series of images, it is (much) faster to use the specific UView Folder Reader (see below).

Metadata stored in the file header (start voltage, temperature, pressure, field of view, micrometer position, date, etc.) is attached to the dataset and visible via **Image > Show Info**.

### UView Folder Reader

**Plugins > LEEMandPEEM > UView Folder Reader**

Opens a folder of `.dat` files as an ImageJ stack. A dialog allows filtering by filename substring and selecting a range and increment (options are remembered between runs). Each slice label contains the metadata extracted from that file's header in `key=value` format, which can be used by **Plot Intensity vs Tag**.

#### Companion CSV support (Solaris DEMETER beamline)

If the folder contains a CSV file whose name does not include the word `meta`, the plugin reads it and merges its per-image metadata into the stack. The rows of the CSV are matched to the `.dat` files by position (both sorted alphabetically), so the first data row corresponds to the first image, and so on.

The following columns are recognised and added as slice label tags:

| CSV column | Slice label tag | Description |
|------------|-----------------|-------------|
| `Energy`   | `Energy (eV)`   | Photon energy set point |
| `M4b`      | `M4b`           | M4 mirror drain current (flux monitor) |

This matches the metadata CSV format produced by the PEEM data acquisition system at the [Solaris](https://www.synchrotron.uj.edu.pl) DEMETER beamline, where each XAS/NEXAFS scan folder contains a CSV file with one row per image alongside columns for photon energy, flux monitors, and other beamline parameters. A typical file looks like:

```
Energy,ROI1_Intensity,ROI2_Intensity,...,M4b,...
520.024,34.16,...,5875.16,...
520.501,34.15,...,5901.09,...
...
```

Both comma- and semicolon-delimited files are supported. UTF-8 BOM (added by Excel on Windows) is stripped automatically.

Once loaded, `Energy (eV)` and `M4b` appear in the **Plot Intensity vs Tag** tag dropdowns alongside the metadata embedded in the `.dat` files themselves.

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

### Process Spectrum

**Plugins > LEEMandPEEM > Process Spectrum**

Post-processes an active plot window produced by **Plot Intensity vs Tag**. Operations applied in order:

1. **Pre-edge subtraction** — subtracts the average intensity around a chosen energy
2. **Post-edge normalisation** — divides by the average intensity around a second chosen energy
3. **Difference** — curve 1 minus curve 2 (available when the plot has two or more curves)

Each energy value is averaged over the five nearest points for robustness against noise. The processed curves are shown in a new plot window. All settings are remembered between runs.

### Quick Normalize

**Plugins > LEEMandPEEM > Quick Normalize**

One-shot beamtime command that chains **Plot Intensity vs Tag** and **Process Spectrum** using their last-used settings, with no dialogs. Configure both plugins once on a representative dataset, then run Quick Normalize on every subsequent dataset for an instant normalised spectrum. Results are logged to the Fiji log window.

## Typical workflow: XAS/NEXAFS at Solaris DEMETER

1. Open the scan folder with **UView Folder Reader**. If the beamline CSV is present, `Energy (eV)` and `M4b` are automatically added to every slice.
2. Draw one or more ROIs on the stack (use the ROI Manager for multiple regions).
3. Run **Plot Intensity vs Tag**:
   - Set *X tag* to `Energy (eV)`.
   - Set *Y tag* to `M4b` and *Y formula* to `y / t` to normalise the intensity by the incident flux.
   - With multiple ROIs open, all curves are overlaid on the same plot with a legend.
4. Run **Process Spectrum** on the resulting plot:
   - Set a pre-edge energy (flat region before the absorption edge) and a post-edge energy (region above the edge).
   - Enable *Pre-edge subtraction* and *Post-edge normalisation* to obtain a normalised NEXAFS spectrum (0 before edge, 1 after edge).
   - Optionally enable *Difference* to compute the XMCD signal (two-ROI or two-curve plots).
5. For subsequent datasets at the same beamline, run **Quick Normalize** directly — it reuses the tag, formula, and energy settings from the previous run with no dialogs.
6. Check **Save CSV** in Plot Intensity vs Tag to export raw curves for further processing, e.g. with Python/matplotlib.

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
