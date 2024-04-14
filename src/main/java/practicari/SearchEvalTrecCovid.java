package practicari;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.search.similarities.Similarity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class SearchEvalTrecCovid {

    public static void main(String[] args) throws ParseException {
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
                    cut = Integer.parseInt(args[++i]);
                    break;
                case "-top":
                    top = Integer.parseInt(args[++i]);
                    break;
                case "-queries":
                    queriesOption = args[++i];
                    break;
                default:
                    System.err.println("Opción desconocida: " + args[i]);
                    break;
            }
        }

        // Validar argumentos
        if (searchModel == null || indexPath == null || queriesOption == null) {
            System.err.println("Uso: java SearchEvalTrecCovid -search <jm/bm25> <lambda/k1> -index <ruta> -cut <n> -top <m> -queries <all/int1/int1-int2>");
            System.exit(1);
        }

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
             IndexReader reader = DirectoryReader.open(dir)) {

            // Crear el buscador
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(similarity);

            // Procesamiento de las queries
            QueryParser queryParser = new MultiFieldQueryParser(new String[]{"text"}, analyzer);

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
            }

        } catch (IOException e) {
            System.err.println("Error al abrir el índice: " + e.getMessage());
        }
    }

    // Método para leer los juicios de relevancia del archivo test.tsv
    private static Map<String, Integer> readRelevanceJudgments(String filePath) throws IOException {
        Map<String, Integer> relevanceJudgments = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(new File(filePath)))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("\t");
                relevanceJudgments.put(parts[0], Integer.parseInt(parts[1]));
            }
        }
        return relevanceJudgments;
    }

    // Método para obtener las queries dependiendo de la opción seleccionada
    private static Iterable<String> getQueries(String queriesOption) {
        // Implementa la lógica para obtener las queries dependiendo de la opción seleccionada
        // Puedes leer el archivo queries.jsonl y obtener las queries
        return Arrays.asList("query1", "query2", "query3"); // Temporal, reemplaza esto con la lógica adecuada
    }
}
