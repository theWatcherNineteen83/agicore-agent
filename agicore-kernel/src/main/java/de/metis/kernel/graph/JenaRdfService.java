package de.metis.kernel.graph;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.TDB2Factory;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * RDF graph-based knowledge store using Apache Jena TDB2.
 * <p>
 * Stores Metis beliefs as RDF triples, enabling SPARQL queries for
 * causal reasoning, graph traversal, and structured knowledge retrieval.
 * <p>
 * Replaces/supplements the cosine-similarity-based WorldModel for
 * structured causal knowledge. TDB2 provides ACID transactions and
 * scales to billions of triples.
 * <p>
 * Configuration:
 * <ul>
 *   <li>{@code metis.jena.dir} — TDB2 database directory (default: ./jena-db)</li>
 *   <li>{@code metis.jena.enabled} — enable Jena graph store (default: true)</li>
 * </ul>
 */
public class JenaRdfService {

    private static final Logger LOG = Logger.getLogger(JenaRdfService.class.getName());

    // Metis namespace
    public static final String NS = "http://metis.agi/knowledge#";
    public static final String NS_BELIEF = NS + "belief/";

    // Common predicates
    public static final Property P_CAUSES = ResourceFactory.createProperty(NS + "causes");
    public static final Property P_INFLUENCES = ResourceFactory.createProperty(NS + "influences");
    public static final Property P_CORRELATES = ResourceFactory.createProperty(NS + "correlatesWith");
    public static final Property P_HAS_CONFIDENCE = ResourceFactory.createProperty(NS + "confidence");
    public static final Property P_HAS_SOURCE = ResourceFactory.createProperty(NS + "source");
    public static final Property P_HAS_VALUE = ResourceFactory.createProperty(NS + "value");
    public static final Property P_OBSERVED_AT = ResourceFactory.createProperty(NS + "observedAt");
    public static final Property P_SUBJECT = ResourceFactory.createProperty(NS + "subject");
    public static final Property P_PREDICATE = ResourceFactory.createProperty(NS + "predicate");
    public static final Property P_OBJECT = ResourceFactory.createProperty(NS + "object");

    // Common classes
    public static final Resource C_BELIEF = ResourceFactory.createResource(NS + "Belief");
    public static final Resource C_CAUSAL_HYPOTHESIS = ResourceFactory.createResource(NS + "CausalHypothesis");
    public static final Resource C_OBSERVATION = ResourceFactory.createResource(NS + "Observation");

    private static volatile JenaRdfService INSTANCE;

    private final boolean enabled;
    private final Path dbDir;
    private Dataset dataset;
    private volatile boolean initialized;

    private JenaRdfService() {
        this.enabled = Boolean.parseBoolean(System.getProperty("metis.jena.enabled", "true"));
        this.dbDir = Path.of(System.getProperty("metis.jena.dir", "./jena-db"));

        if (enabled) {
            LOG.info(() -> "Jena RDF service configured: dir=" + dbDir.toAbsolutePath());
        }
    }

    public static JenaRdfService getInstance() {
        if (INSTANCE == null) {
            synchronized (JenaRdfService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new JenaRdfService();
                }
            }
        }
        return INSTANCE;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isInitialized() { return initialized; }

    /**
     * Initialize the TDB2 database.
     */
    public synchronized void init() {
        if (!enabled || initialized) return;

        try {
            dbDir.toFile().mkdirs();
            dataset = TDB2Factory.connectDataset(dbDir.toString());
            LOG.info(() -> "Jena TDB2 initialized: " + dbDir.toAbsolutePath());
            initialized = true;
        } catch (Exception e) {
            LOG.severe("Jena TDB2 init failed: " + e.getMessage());
            throw new RuntimeException("Jena TDB2 init failed", e);
        }
    }

    /**
     * Shutdown the TDB2 database.
     */
    public synchronized void shutdown() {
        if (dataset != null) {
            try {
                dataset.close();
                LOG.info("Jena TDB2 closed");
            } catch (Exception e) {
                LOG.warning("Jena shutdown error: " + e.getMessage());
            }
            dataset = null;
        }
        initialized = false;
    }

    // ── Belief Operations ──

    /**
     * Store a belief as an RDF triple: (beliefId, predicate, object).
     *
     * @return the created belief resource
     */
    public Resource storeBelief(String beliefId, String predicate, String objectValue,
                                 double confidence, String source) {
        if (!checkReady()) return null;

        Txn.executeWrite(dataset, () -> {
            Model model = dataset.getDefaultModel();
            Resource belief = model.createResource(NS_BELIEF + beliefId);

            model.add(belief, RDF.type, C_BELIEF);
            model.add(belief, P_SUBJECT, model.createLiteral(beliefId));
            model.add(belief, P_PREDICATE, model.createLiteral(predicate));
            model.add(belief, P_OBJECT, model.createLiteral(objectValue));
            model.add(belief, P_HAS_CONFIDENCE,
                    model.createTypedLiteral(confidence));
            model.add(belief, P_HAS_SOURCE, model.createLiteral(source));
            model.add(belief, P_OBSERVED_AT,
                    model.createTypedLiteral(Instant.now().toString()));
        });

        LOG.fine(() -> "Stored belief: " + beliefId + " [" + predicate + "] -> " + objectValue);
        return ResourceFactory.createResource(NS_BELIEF + beliefId);
    }

    /**
     * Store a causal hypothesis: X causes Y with confidence.
     */
    public Resource storeCausalLink(String causeVariable, String effectVariable,
                                     double confidence, String evidence) {
        if (!checkReady()) return null;

        String id = "causal-" + UUID.randomUUID().toString().substring(0, 8);
        Txn.executeWrite(dataset, () -> {
            Model model = dataset.getDefaultModel();
            Resource hypothesis = model.createResource(NS + "hypothesis/" + id);
            Resource cause = model.createResource(NS + "variable/" + causeVariable);
            Resource effect = model.createResource(NS + "variable/" + effectVariable);

            model.add(hypothesis, RDF.type, C_CAUSAL_HYPOTHESIS);
            model.add(hypothesis, P_CAUSES, effect);
            model.add(cause, P_CAUSES, effect);
            model.add(hypothesis, P_HAS_CONFIDENCE,
                    model.createTypedLiteral(confidence));
            model.add(hypothesis, P_HAS_SOURCE, model.createLiteral(evidence));
            model.add(hypothesis, P_OBSERVED_AT,
                    model.createTypedLiteral(Instant.now().toString()));
        });

        LOG.info(() -> "Stored causal link: " + causeVariable + "->" + effectVariable
                + " (conf=" + String.format("%.2f", confidence) + ")");
        return ResourceFactory.createResource(NS_BELIEF + id);
    }

    // ── Query Operations ──

    /**
     * Find all known causes of a variable.
     *
     * @return list of (causeVariable, confidence, evidence)
     */
    public List<Map<String, String>> queryCauses(String effectVariable) {
        if (!checkReady()) return List.of();

        String sparql = """
                PREFIX metis: <%s>
                SELECT ?cause ?confidence ?evidence WHERE {
                    ?cause metis:causes <%s%s> .
                    MINUS { ?cause a metis:CausalHypothesis }
                    ?h a metis:CausalHypothesis ;
                       metis:causes <%s%s> ;
                       metis:confidence ?confidence ;
                       metis:source ?evidence .
                }
                ORDER BY DESC(?confidence)
                """.formatted(NS, NS, "variable/" + effectVariable,
                    NS, "variable/" + effectVariable);

        return executeQuery(sparql, List.of("cause", "confidence", "evidence"));
    }

    /**
     * Find all effects of a variable (forward causal inference).
     */
    public List<Map<String, String>> queryEffects(String causeVariable) {
        if (!checkReady()) return List.of();

        String sparql = """
                PREFIX metis: <%s>
                SELECT ?effect ?confidence WHERE {
                    <%s%s> metis:causes ?effect .
                    ?h a metis:CausalHypothesis ;
                       metis:causes ?effect ;
                       metis:confidence ?confidence .
                }
                ORDER BY DESC(?confidence)
                """.formatted(NS, NS, "variable/" + causeVariable);

        return executeQuery(sparql, List.of("effect", "confidence"));
    }

    /**
     * Find beliefs matching a predicate (like querying WorldModel).
     */
    public List<Map<String, String>> queryBeliefs(String predicate, int limit) {
        if (!checkReady()) return List.of();

        String sparql = """
                PREFIX metis: <%s>
                SELECT ?subject ?object ?confidence ?source WHERE {
                    ?belief a metis:Belief ;
                            metis:predicate ?pred ;
                            metis:object ?object ;
                            metis:confidence ?confidence ;
                            metis:source ?source ;
                            metis:subject ?subject .
                    FILTER(?pred = "%s")
                }
                ORDER BY DESC(?confidence)
                LIMIT %d
                """.formatted(NS, predicate, limit);

        return executeQuery(sparql,
                List.of("subject", "object", "confidence", "source"));
    }

    /**
     * Find the transitive causal closure: all variables reachable from startVariable
     * via causal links, up to maxDepth hops.
     */
    public List<String> causalClosure(String startVariable, int maxDepth) {
        if (!checkReady() || maxDepth < 1) return List.of();

        String sparql = """
                PREFIX metis: <%s>
                SELECT DISTINCT ?effect WHERE {
                    <%s%s> metis:causes+ ?effect .
                }
                """.formatted(NS, NS, "variable/" + startVariable);

        return executeQuery(sparql, List.of("effect"))
                .stream()
                .map(m -> m.get("effect"))
                .toList();
    }

    /**
     * Get the total number of stored triples.
     */
    public long tripleCount() {
        if (!checkReady()) return 0;

        try {
            return Txn.calculateRead(dataset, () -> dataset.getDefaultModel().size());
        } catch (Exception e) {
            LOG.warning("Triple count failed: " + e.getMessage());
            return -1;
        }
    }

    // ── Internal ──

    private List<Map<String, String>> executeQuery(String sparql, List<String> vars) {
        var results = new ArrayList<Map<String, String>>();
        Txn.executeRead(dataset, () -> {
            try (QueryExecution qe = QueryExecution.dataset(dataset)
                    .query(sparql)
                    .build()) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    var row = new LinkedHashMap<String, String>();
                    for (String var : vars) {
                        var node = sol.get(var);
                        row.put(var, node != null ? node.toString() : "");
                    }
                    results.add(row);
                }
            }
        });
        return results;
    }

    private boolean checkReady() {
        if (!enabled) {
            LOG.fine("Jena not enabled");
            return false;
        }
        if (!initialized) {
            LOG.warning("Jena not initialized — call init() first");
            return false;
        }
        return true;
    }
}
