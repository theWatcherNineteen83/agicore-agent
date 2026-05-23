package de.metis.modules;

import de.metis.kernel.embedding.InMemoryVectorIndex;
import de.metis.modules.evolution.OllamaEmbeddingService;

/**
 * Quick test of the embedding + vector index system.
 */
public final class EmbeddingDemo {
    public static void main(String[] args) {
        System.out.println("═══ Embedding + Vector Index Demo ═══\n");

        var embedder = new OllamaEmbeddingService();
        var index = new InMemoryVectorIndex();

        // Embed and index some goals
        String[] goals = {
                "Run a shell command to check system status",
                "Execute shell uptime verification",
                "Send HTTP request to health endpoint",
                "Query REST API for user data",
                "Analyze sensor data from temperature probe"
        };

        System.out.println("Embedding " + goals.length + " goals via llama3.2:3b...");
        for (int i = 0; i < goals.length; i++) {
            double[] vec = embedder.embed(goals[i]);
            index.insert("goal-" + i, vec);
            System.out.println("  [" + i + "] " + vec.length + " dims: " + goals[i]);
        }

        System.out.println("\nCache: " + embedder.cacheHits() + " hits, "
                + embedder.embedCount() + " embeddings");

        // Search for similar goals
        String query = "Check system health via command line";
        System.out.println("\nQuery: \"" + query + "\"");
        double[] qVec = embedder.embed(query);

        System.out.println("\nTop-3 similar goals:");
        for (String key : index.search(qVec, 3)) {
            int idx = Integer.parseInt(key.substring(5));
            System.out.println("  " + key + ": " + goals[idx]);
        }

        System.out.println("\n✅ Embedding demo complete.");
    }
}
