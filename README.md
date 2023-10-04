[![Extension docs](https://img.shields.io/badge/docs-qupath_bioimage.io-red)](https://qupath.readthedocs.io/en/stable/docs/deep/bioimage.html)
[![Forum](https://img.shields.io/badge/forum-image.sc-green)](https://forum.image.sc/tag/qupath)
[![Downloads (latest release)](https://img.shields.io/github/downloads-pre/qupath/qupath-extension-bioimageio/latest/total)](https://github.com/qupath/qupath-extension-bioimageio/releases/latest)
[![Downloads (all releases)](https://img.shields.io/github/downloads/qupath/qupath-extension-bioimageio/total)](https://github.com/qupath/qupath-extension-bioimageio/releases)

# QuPath Bioimage Model Zoo extension

This is a QuPath extension for working with the Bioimage Model Zoo (https://bioimage.io).

You can download the .jar file from [qupath-extension-bioimageio-0.1.0.jar](https://github.com/qupath/qupath-extension-bioimageio/releases/download/v0.1.0/qupath-extension-bioimageio-0.1.0.jar)

It's early and experimental - subject to change in later releases.

The main aim is to enable models kept in the Zoo to be imported into some QuPath-friendly form.

Currently, this extension adds a command to create pixel classifers from supported models:

* *Extensions &rarr; Bioimage Model Zoo &rarr; Create pixel classifier (Bioimage Model Zoo)*

Along the way, it adds some extra flexibility: choosing the resolution at which the model is applied, and adapting the input channels and output classifications.

Only a subset of models are currently supported, partly because 

1. not all models are Java-friendly, and
2. some models require accessing all the pixels in the image... but QuPath is designed for huge images, and doesn't easily support these kinds of global calculations yet

As a result, applying the model in QuPath can sometimes give a different result to applying it in other software.
The goal is to improve this in future releases.
