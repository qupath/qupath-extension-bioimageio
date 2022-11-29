# QuPath Bioimage Model Zoo extension

This is a QuPath extension for working with the Bioimage Model Zoo (https://bioimage.io).

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