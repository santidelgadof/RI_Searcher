package practicari;

import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
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
            Map<Integer, Map<String, Integer>> relevances = readTsv(testFile);
            int numQueries = queries.size();

            double sumP = 0;
            double sumRecall = 0;
            double sumAP = 0;
            double sumRR = 0;

            // Búsqueda y evaluación de las queries
            for (QueryJsonl query : queries) {
                System.out.println("Query: " + query.metadata().query());
                Query q = queryParser.parse(query.metadata().query());
                Map<String, Integer> thisRelevances = relevances.get(query.id());

                // Ranking de documentos  al hacer una búsqueda
                TopDocs topDocs = searcher.search(q, cut);      // sacamos los n top docs para las métricas
                List<ScoreDoc> scoreDocs = List.of(topDocs.scoreDocs);
                int relevantN = 0;
                int relevantQuery = 0;
                double sumAccuracies = 0;
                int firstRelevant = 0;

                for(String corpusID : thisRelevances.keySet()) {
                    // para cada doc, vemos si es relevante para esta query
                    if(thisRelevances.get(corpusID) > 0)
                        relevantQuery++;
                }

                if (relevantQuery == 0) {
                    numQueries--;    // no tenemos en cuenta las queries sin resultados para las métricas
                } else {
                    int rankingPos = 0;
                    for(ScoreDoc scoreDoc : scoreDocs) {    // n docs in topDocs
                        // buscamos cada documento de topDocs
                        String corpusID = searcher.doc(scoreDoc.doc).getField("id").stringValue();
                        int relevance = thisRelevances.get(corpusID);
                        rankingPos++;
                        if(relevance > 0) {
                            relevantN++;
                            // calcular precision
                            sumAccuracies += (double) relevantN /cut;
                            if(firstRelevant == 0)
                                firstRelevant = rankingPos;
                        }
                    }

                    double p = (double) relevantN / cut;
                    double recall = relevantQuery == 0? 0 : (double) relevantN / relevantQuery;
                    double ap = relevantQuery == 0? 0 : sumAccuracies / relevantQuery;
                    int rr = firstRelevant == 0? 0 : 1/firstRelevant;

                    // sumar para luego calcular las métricas globales
                    sumP += p;
                    sumRecall += recall;
                    sumAP += ap;
                    sumRR += rr;
                }



                // print query metrics



            }

            double mp = sumP / numQueries;
            double meanRecall = sumRecall / numQueries;
            double map = sumAP / numQueries;
            double mrr = sumRR / numQueries;

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
            }*/

        } catch (IOException e) {
            System.err.println("Error al abrir el índice: " + e.getMessage());
        } catch (ParseException e) {
            System.err.println("Error al parsear una query: " + e.getMessage());
        }
    }

    private static Map<Integer, Map<String, Integer>> readTsv(File test) {
        Map<Integer, Map<String, Integer>> data = new HashMap<>();

        try (BufferedReader tsvReader = new BufferedReader(new FileReader(test))) {
            String line;
            while ((line = tsvReader.readLine()) != null) {
                String[] lineItems = line.split("\t");
                int queryId = Integer.parseInt(lineItems[0]);  // TODO: funcion parseInt

                Map<String, Integer> map = data.containsKey(queryId)? data.get(queryId) : new HashMap<>();
                int relev = Integer.parseInt(lineItems[2]); // TODO parseint
                map.put(lineItems[1], relev);
                data.put(queryId, map);
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

}
