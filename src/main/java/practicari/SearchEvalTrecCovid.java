package practicari;

import com.fasterxml.jackson.databind.MappingIterator;
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
            List<QueryJsonl> queries = new LinkedList<>();
            MappingIterator<QueryJsonl> itr = queryReader.readValues(queryFile);
            if(queriesOption.equals("all"))     // si hay que leer todas las queries
                queries = itr.readAll();
            else if (queriesOption.contains("-")) {      // si es un rango de queries
                String[] parts = queriesOption.split("-");
                int q1 = Integer.parseInt(parts[0]);
                int q2 = Integer.parseInt(parts[1]);
                QueryJsonl query;

                while (itr.hasNext()) {
                    query = itr.next();
                    if (query.id() > q1 && query.id() < q2)
                        queries.add(query);
                }
            } else {        // si es una única query
                int q = Integer.parseInt(queriesOption);
                QueryJsonl query;

                while (itr.hasNext()) {
                    query = itr.next();
                    if (query.id()  == q) {
                        queries.add(query);
                        break;
                    }
                }

                if(queries.isEmpty()) {
                    System.out.println("La query especificada no existe.");
                    System.exit(1);
                }
            }


            // Procesamiento de las queries
            QueryParser queryParser = new QueryParser("text", analyzer);

            // Abrimos los writers
            PrintWriter txtWriter = new PrintWriter("TREC-COVID." + searchModel +
                    "." + top + ".hits." + (lambda!=0? "lambda." + lambda : "k1." + k1) + ".q" +
                    queriesOption + ".txt");
            PrintWriter csvWriter = new PrintWriter("TREC-COVID." + searchModel +
                    "." + cut + ".cut." + (lambda!=0? "lambda." + lambda : "k1." + k1) + ".q" +
                    queriesOption + ".csv");

            // cabeceras del csv
            csvWriter.println("Query,P@" + cut + ",Recall@" + cut + ",AP@" + cut + ",RR@" + cut);

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
                txtWriter.println("Query: " + query.metadata().query());
                // TODO: analizar que todas las queries se hagan bien con el parser
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

                double p = 0;
                double recall = 0;
                double ap = 0;
                int rr = 0;

                if (relevantQuery == 0) {
                    numQueries--;    // no tenemos en cuenta para las métricas las queries sin resultados

                    for(ScoreDoc scoreDoc : scoreDocs) {    // n docs in topDocs
                        // buscamos cada documento de topDocs
                        Document doc = searcher.doc(scoreDoc.doc);

                        // print data for each doc:
                        String print = getStringIndexedData(doc, scoreDoc, thisRelevances.get(doc.get("id")));
                        System.out.print(print);
                        txtWriter.print(print);
                    }
                } else {
                    int rankingPos = 0;
                    for(ScoreDoc scoreDoc : scoreDocs) {    // n docs in topDocs
                        // buscamos cada documento de topDocs
                        Document doc = searcher.doc(scoreDoc.doc);
                        String corpusID = doc.get("id");
                        int relevance = thisRelevances.get(corpusID);
                        rankingPos++;
                        if(relevance > 0) {
                            relevantN++;
                            // calcular precision
                            sumAccuracies += (double) relevantN / rankingPos;
                            if(firstRelevant == 0)
                                firstRelevant = rankingPos;
                        }

                        // print doc data:
                        if (rankingPos < top) {
                            String print = getStringIndexedData(doc, scoreDoc, relevance);
                            System.out.print(print);
                            txtWriter.print(print);
                        }
                    }

                    // cálculo de métricas. Los denominadores nunca serán 0 por el if-else
                    p = (double) relevantN / cut;
                    recall = (double) relevantN / relevantQuery;
                    ap = sumAccuracies / relevantQuery;
                    rr = 1/firstRelevant;

                    // sumar para luego calcular las métricas globales
                    sumP += p;
                    sumRecall += recall;
                    sumAP += ap;
                    sumRR += rr;
                }

                // print query metrics
                System.out.print("QUERY METRICS:" + System.lineSeparator() + "P@N: " + p + "; Recall@n: "+ recall
                        + "; AP@n: " + ap + "; RR@n: " + rr + System.lineSeparator() + System.lineSeparator());
                txtWriter.print("QUERY METRICS:" + System.lineSeparator() + "P@N: " + p + "; Recall@n: " + recall
                        + "; AP@n: " + ap + "; RR@n: " + rr + System.lineSeparator() + System.lineSeparator());
                csvWriter.println(query.id() + "," + p + "," + recall + "," + ap + "," + rr);
            }

            // global metrics
            double mp = sumP / numQueries;
            double meanRecall = sumRecall / numQueries;
            double map = sumAP / numQueries;
            double mrr = sumRR / numQueries;

            System.out.println("GLOBAL METRICS:" + System.lineSeparator() + "Mean P@N: " + mp
                    + "; Mean Recall@n: " + meanRecall + "; MAP@n: " + map + "; MRR@n: " + mrr);
            txtWriter.println("GLOBAL METRICS:" + System.lineSeparator() + "Mean P@N: " + mp + "; Mean Recall@n: "
                    + meanRecall + "; MAP@n: " + map + "; MRR@n: " + mrr);

            txtWriter.flush();
            txtWriter.close();
            csvWriter.flush();
            csvWriter.close();
        } catch (IOException e) {
            System.err.println("Excepción de E/S: " + e.getMessage());
        } catch (ParseException e) {
            System.err.println("Error de parsing: " + e.getMessage());
        }
    }

    private static String getStringIndexedData(Document doc, ScoreDoc score, Integer relevance) {
        String str = "ID: " + doc.get("id") + System.lineSeparator();
        str += "Title: " + doc.get("title") + System.lineSeparator();
        str += "Text: " + doc.get("text") + System.lineSeparator();
        str += "Url: " + doc.get("url") + System.lineSeparator();
        str += "Pubmed_id: " + doc.get("pubmed_id") + System.lineSeparator();
        str += "Score: " + score + System.lineSeparator();

        if(relevance == 0)
            str += "Documento no relevante." + System.lineSeparator() + System.lineSeparator();
        else if (relevance == 1)
            str += "Documento parcialmente relevante." + System.lineSeparator() + System.lineSeparator();
        else
            str += "Documento relevante." + System.lineSeparator() + System.lineSeparator();
        return str;
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
