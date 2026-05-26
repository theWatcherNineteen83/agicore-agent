package de.metis.modules.hardware;

import de.metis.kernel.action.Action;
import de.metis.kernel.action.ActionResult;

import deepnetts.net.FeedForwardNetwork;
import deepnetts.net.layers.activation.ActivationType;
import deepnetts.net.loss.LossType;
import deepnetts.data.TabularDataSet;
import deepnetts.data.preprocessing.scale.MinMaxScaler;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Deep Netts neural network service for Metis.
 * <p>
 * Provides pure-Java neural network training and inference.
 * Uses Deep Netts Community Edition (com.deepnetts:deepnetts-core:1.13.2).
 */
public class DeepNettsAction implements Action {

    private static final Logger LOG = Logger.getLogger(DeepNettsAction.class.getName());
    private static final String NAME = "deepnetts";

    private FeedForwardNetwork lastNetwork;
    private String lastNetworkDescription = "none";
    private int lastParams = 0;

    @Override
    public String name() { return NAME; }

    @Override
    public ActionResult execute() {
        Instant start = Instant.now();
        try {
            // Create and train a simple XOR network
            var net = FeedForwardNetwork.builder()
                    .addInputLayer(2)
                    .addFullyConnectedLayer(4, ActivationType.TANH)
                    .addOutputLayer(1, ActivationType.SIGMOID)
                    .lossFunction(LossType.MEAN_SQUARED_ERROR)
                    .build();

            // Create XOR training data
            var ds = new TabularDataSet(2, 1);
            ds.add(new TabularDataSet.Item(new float[]{0, 0}, new float[]{0}));
            ds.add(new TabularDataSet.Item(new float[]{0, 1}, new float[]{1}));
            ds.add(new TabularDataSet.Item(new float[]{1, 0}, new float[]{1}));
            ds.add(new TabularDataSet.Item(new float[]{1, 1}, new float[]{0}));

            // Normalize
            new MinMaxScaler(ds).apply(ds);

            // Train
            LOG.info("Training XOR network...");
            net.train(ds);

            // Evaluate
            int correct = 0;
            float[][] xorInputs = {{0,0},{0,1},{1,0},{1,1}};
            float[] xorTargets = {0,1,1,0};
            for (int i = 0; i < 4; i++) {
                float[] input = xorInputs[i];
                float[] predicted = net.predict(input);
                if (Math.abs(xorTargets[i] - predicted[0]) < 0.5) correct++;
            }

            float accuracy = (float) correct / 4;  // 4 XOR patterns
            lastNetwork = net;
            lastParams = estimateParams(net);
            lastNetworkDescription = String.format(
                    "XOR Network: 2→4→1, ~%d params, %.1f%% accuracy",
                    lastParams, accuracy * 100);

            String result = String.format("""
                    === Deep Netts Neural Network ===
                    %s
                    Framework: Deep Netts Community Edition 1.13.2
                    Capabilities: Feed-forward networks, classification, regression
                    GPU-accelerated: No (CPU-only in Community Edition)
                    
                    Metis can now create and train neural networks in pure Java.
                    """, lastNetworkDescription);

            LOG.info(result);
            return ActionResult.ok(NAME, result, start);
        } catch (Exception e) {
            LOG.warning("Deep Netts error: " + e.getMessage());
            return ActionResult.fail(NAME, e.getMessage(), start);
        }
    }

    private int estimateParams(FeedForwardNetwork net) {
        // Rough estimate: each fully connected layer: inputs*outputs + outputs (biases)
        int params = 0;
        int prevSize = 2;  // input layer
        for (int layerSize : new int[]{4, 1}) {  // hidden, output
            params += prevSize * layerSize + layerSize;
            prevSize = layerSize;
        }
        return params;
    }

    public FeedForwardNetwork getLastNetwork() { return lastNetwork; }

    public String getLastNetworkDescription() { return lastNetworkDescription; }
}
