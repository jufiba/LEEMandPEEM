# LEEMandPEEM

This will include an assortment of ImageJ(2) plugins intented for use on low-energy electron and photoemission microscopy images.

For now, it includes the following:

getXYZ: using three images, each of a vector component along an specific direction to obtain three images with the X,Y and Z component of the said vector. The three directions do not need to be orthogonal, but they need to define a 3D reference frame, i.e. they must not be coplanar. This is mostly intended for the reconstruction of the magnetization either from X-ray magnetic circular dichroism images measured at different x-ray incidence directions, or from spin-polarized LEEM images acquired at different spin orientations.

toSpherical: selecting three images which correspond to the X,Y and Z component of a 3D vector, calculate the magnetitude, azimuthal and polar images. The latter can be obtained in degrees or radians.
