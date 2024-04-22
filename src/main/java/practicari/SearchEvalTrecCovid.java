package practicari;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.search.similarities.Similarity;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;

public class SearchEvalTrecCovid {
    private static final String queryFilePath = "trec-covid" + File.separator + "queries.jsonl";
    private static final String testFilePath = "trec-covid" + File.separator + "qrels" + File.separator + "test.tsv";

    public static void main(String[] args) {
        // Parseo de argumentos de línea de comandos
        String searchModel = null;
        double lambda = 0.0; // Valor por defecto para JM
        float k1 = 1.2f; // Valor por defecto para BM25
        String indexPath = null;
        int cut = 10; // Valor por defecto para el corte en el ranking
        int top = 10; // Valor por defecto para el top de documentos
        String queriesOption = null; // Opciones para seleccionar qué queries evaluar

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-search":
                    searchModel = args[++i];
                    if (searchModel.equalsIgnoreCase("jm")) {
                        lambda = Double.parseDouble(args[++i]);
                    } else if (searchModel.equalsIgnoreCase("bm25")) {
                        k1 = Float.parseFloat(args[++i]);
                    }
                    break;
                case "-index":
                    indexPath = args[++i];
                    break;
                case "-cut":
                    cut = Integer.parseInt(args[++i]);      // TODO: handle parse exceptions
                    break;
                case "-top":
                    top = Integer.parseInt(args[++i]);
                    break;
                case "-queries":
                    queriesOption = args[++i];
                    break;
                default:
                    System.err.println("Opción desconocida: " + args[i]);
                    System.exit(0);
                    break;
            }
        }

        // Validar argumentos
        if (searchModel == null || indexPath == null || queriesOption == null) {
            System.err.println("Uso: java SearchEvalTrecCovid -search <jm/bm25> <lambda/k1> -index <ruta> -cut <n> -top <m> -queries <all/int1/int1-int2>");
            System.exit(1);
        }

        // TODO: añadir validación de entradas (cut>0, top>0, etc)

        // Configurar el analizador
        Analyzer analyzer = new StandardAnalyzer();

        // Configurar la similitud según el modelo especificado
        Similarity similarity;
        if (searchModel.equalsIgnoreCase("jm")) {
            similarity = new LMJelinekMercerSimilarity((float) lambda);
        } else {
            similarity = new BM25Similarity(k1, 0.75f); // Usando b=0.75 por defecto
        }

        // Abrir el índice
        try (Directory dir = FSDirectory.open(Paths.get(indexPath));
             IndexReader indexReader = DirectoryReader.open(dir)) {

            // Crear el buscador
            IndexSearcher searcher = new IndexSearcher(indexReader);
            searcher.setSimilarity(similarity);

            File queryFile = new File(queryFilePath);
            ObjectReader queryReader = JsonMapper.builder().findAndAddModules().build()
                    .readerFor(QueryJsonl.class);
            final List<QueryJsonl> queries;
            queries = queryReader.<QueryJsonl>readValues(queryFile).readAll();

            // Procesamiento de las queries
            QueryParser queryParser = new QueryParser("text", analyzer);

            // Leer el archivo de juicios de relevancia (test.tsv)
            File testFile = new File(testFilePath);
            Map<KeyPair, Integer> relevances = readTsv(testFile);

            // Búsqueda y evaluación de las queries
            for (QueryJsonl query : queries) {
                Query q = queryParser.parse(query.metadata().query());

                // Ranking de documentos  al hacer una búsqueda
                TopDocs topDocs = searcher.search(q, cut);
                List<ScoreDoc> scoreDocs = List.of(topDocs.scoreDocs);
                List<String> topDocsIDs = new LinkedList<>();
                int numRelevantDocsInRanking = 0;
                int numRelevantDocsQuery = 0;
                List<Double> rankingAccuracies = new LinkedList<>();

                for(ScoreDoc scoreDoc : scoreDocs) {
                    // buscamos cada documento de topDocs y metemos en una lista sus IDs del corpus
                    topDocsIDs.add(searcher.doc(scoreDoc.doc).getField("id").stringValue());
                }

                for(Map.Entry<KeyPair, Integer> entry : relevances.entrySet()) {
                    KeyPair key = entry.getKey();

                    // relevancias para esta query
                    if(key.query_id == query.id()) {
                        Integer relevance = entry.getValue();
                        if(relevance > 0)
                            numRelevantDocsQuery++; // si la fila del test corresponde a esta query, guardamos nº de filas relevantes
                        if (topDocsIDs.contains(key.corpus_id)) {
                            if (relevance > 0) {
                                numRelevantDocsInRanking++; // si la fila del test corresponde a esta query y el documento está en topDocs, contamos nº de filas relevantes del ranking
                                // TODO: calcular precisiones y meterlas en rankingAccuracies
                            }
                        }
                    }

                }
                double sumAccuracies = sumList(rankingAccuracies);

                // Evaluación de métricas
                float p = numRelevantDocsInRanking/cut;
                int recall = numRelevantDocsInRanking/numRelevantDocsQuery;
                double ap = sumAccuracies/numRelevantDocsQuery; // TODO: nº de relevantes por query se refiere a nº de relevantes en esta query??
            }











            /*
            // Búsqueda y evaluación de las queries
            try {
                // Leer el archivo de juicios de relevancia (test.tsv)
                Map<String, Integer> relevanceJudgments = readRelevanceJudgments("test.tsv");

                // Procesamiento de las queries
                for (String query : getQueries(queriesOption)) {
                    Query q = queryParser.parse(query);

                    // Búsqueda de documentos relevantes
                    TopDocs topDocs = searcher.search(q, cut);

                    // Evaluación de métricas
                    int totalRelevant = relevanceJudgments.getOrDefault(query, 0);
                    int retrievedRelevant = 0;
                    double precisionSum = 0.0;
                    double averagePrecision = 0.0;
                    double reciprocalRank = 0.0;

                    ScoreDoc[] hits = topDocs.scoreDocs;
                    for (int i = 0; i < hits.length; i++) {
                        Document doc = searcher.doc(hits[i].doc);
                        int relevance = relevanceJudgments.getOrDefault(doc.get("id"), 0);
                        if (relevance > 0) {
                            retrievedRelevant++;
                            precisionSum += (double) retrievedRelevant / (i + 1);
                            averagePrecision += precisionSum / retrievedRelevant;
                            reciprocalRank = 1.0 / (i + 1);
                        }
                    }

                    double precisionAtN = (double) retrievedRelevant / cut;
                    double recallAtN = (double) retrievedRelevant / totalRelevant;

                    // Visualización de los resultados
                    System.out.println("Query: " + query);
                    // Mostrar documentos relevantes
                    for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                        Document doc = searcher.doc(scoreDoc.doc);
                        System.out.println("Doc: " + doc.get("id") + ", Score: " + scoreDoc.score);
                        // También puedes mostrar otros campos relevantes del documento
                    }
                    System.out.println("Precision@N: " + precisionAtN);
                    System.out.println("Recall@N: " + recallAtN);
                    System.out.println("Average Precision@N: " + averagePrecision);
                    System.out.println("Reciprocal Rank: " + reciprocalRank);
                    System.out.println("-----------------------------");
                }
            } catch (IOException e) {
                System.err.println("Error al procesar las queries: " + e.getMessage());
            } catch (ParseException e) {
                System.err.println("Error al parsear una query: " + e.getMessage());
            }*/

        } catch (IOException e) {
            System.err.println("Error al abrir el índice: " + e.getMessage());
        } catch (ParseException e) {
            System.err.println("Error al parsear una query: " + e.getMessage());
        }
    }

    // calcula la suma de los elementos de una lista
    private static double sumList(List<Double> l) {
        double acc = 0;
        for(double elem : l)
            acc += elem;
        return acc;
    }

    private static Map<KeyPair, Integer> readTsv(File test) {
        Map<KeyPair, Integer> data = new HashMap<>();
        try (BufferedReader tsvReader = new BufferedReader(new FileReader(test))) {
            String line;
            while ((line = tsvReader.readLine()) != null) {
                String[] lineItems = line.split("\t");
                int queryId = Integer.parseInt(lineItems[0]);  // TODO: funcion parseInt
                int relev = Integer.parseInt(lineItems[2]);
                data.put(new KeyPair(queryId, lineItems[1]), relev);
            }
        } catch (FileNotFoundException e) {
            System.err.println("No se ha encontrado el archivo de tests de relevancia.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Excepción de E/S al leer el archivo de tests de relevancia: " + e.getMessage());
            System.exit(1);
        }
        return data;
    }

    // Método para obtener las queries dependiendo de la opción seleccionada
    private static Iterable<String> getQueries(String queriesOption) {
        // Implementa la lógica para obtener las queries dependiendo de la opción seleccionada
        // Puedes leer el archivo queries.jsonl y obtener las queries
        return Arrays.asList("query1", "query2", "query3"); // Temporal, reemplaza esto con la lógica adecuada
    }

    // clase que tiene un par (query_id, corpus_id) para el hashmap de test
    public final static class KeyPair {
        public final int query_id;
        public final String corpus_id;

        private KeyPair(int query_id, String corpus_id) { this.query_id = query_id; this.corpus_id = corpus_id; }

        public KeyPair make(int a, String b) { return new KeyPair(a, b); }

        public int hashCode() {
            return query_id + 31 * (corpus_id != null ? corpus_id.hashCode() : 0);
        }

        public boolean equals(Object o) {
            if (o == null || o.getClass() != this.getClass()) { return false; }
            KeyPair that = (KeyPair) o;
            return (query_id == that.query_id) && corpus_id.equals(that.corpus_id);
        }
    }
}
