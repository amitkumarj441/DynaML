<img align="right" src="images/dynaml_logo3.png" alt="DynaML Logo"
height="200px" style="padding-left: 20px" alt="DynaML Logo"
style="width: 256px;"/>

[![Join the chat at https://gitter.im/DynaML/Lobby](https://badges.gitter.im/DynaML/Lobby.svg)](https://gitter.im/DynaML/Lobby?utm_source=badge&utm_medium=badge&utm_campaign=pr-badge&utm_content=badge)
[![Build Status](https://travis-ci.org/transcendent-ai-labs/DynaML.svg?branch=master)](https://travis-ci.org/transcendent-ai-labs/DynaML)
[![](https://jitpack.io/v/transcendent-ai-labs/DynaML.svg)](https://jitpack.io/#transcendent-ai-labs/DynaML)


DynaML is a Scala environment for conducting research and education in Machine Learning. DynaML comes packaged with a powerful library of classes for various predictive models and a Scala REPL where one can not only build custom models but also play around with data work-flows.

<br/>
<br/>

## Hello World

Refer to the [installation](installation/installation.md) guide for
getting up and running. Start the DynaML shell.

<br/>


![dynaml](images/screenshot.png)

The `data/` directory contains data sets, which are used by the programs in the `dynaml-examples/` module. Lets run a Gaussian Process (GP) regression model on the synthetic 'delve' data set.

```scala
TestGPDelve("RBF", 2.0, 1.0, 500, 1000)
```

![dynaml](images/screenshot-delve.png)

In this example `TestGPDelve` we train a GP model based on the RBF Kernel with its bandwidth/length scale set to `2.0` and the noise level set to `1.0`, we use 500 input output patterns to train and test on an independent sample of 1000 data points. Apart from printing a bunch of evaluation metrics in the console DynaML also generates Javascript plots using Wisp in the browser.

![plots1](https://cloud.githubusercontent.com/assets/1389553/13259040/ff9bfa84-da55-11e5-9325-f58a73ebf532.png)
