package practicari;

import org.apache.commons.math3.exception.*;
import org.apache.commons.math3.stat.inference.TTest;
import org.apache.commons.math3.stat.inference.WilcoxonSignedRankTest;

import java.io.*;
import java.util.LinkedList;
import java.util.List;

public class Compare {
    private static final String usage = "Uso: -test t | wilcoxon <alpha> -results <results1.csv> <results2.csv>\n" +
            "  -test t | wilcoxon <alpha>: test de significancia estadística (t-test o Wilcoxon) y nivel de " +
            "significancia alpha.\n" +
            "  -results <results1.csv> <results2.csv>: results1 y results2 son archivos de resultados " +
            "obtenidos con TrainingTestTrecCovid para la misma métrica y sobre las mismas queries de " +
            "test.\n";
    public static void main(String[] args) {
        String testOption = null;
        String alphaString = null;
        double alpha;
        String results1 = null, results2 = null;


        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-test":
                    testOption = args[++i];
                    alphaString = args[++i];
                    break;
                case "-results":
                    results1 = args[++i];
                    results2 = args[++i];
                    break;
                default:
                    System.out.println("Parámetro desconocido: " + args[i]);
                    System.out.println(usage);
                    System.exit(1);
            }
        }

        if(testOption == null || alphaString == null) {
            System.out.println("El argumento \"test\" y sus parámetros son obligatorios.");
            System.exit(-1);
        } else if(results1 == null || results2 == null) {
            System.out.println("El argumento \"result\" y sus parámetros son obligatorios.");
            System.exit(-1);
        }

        // parsear alpha
        alpha = tryParseDouble(alphaString, "Parámetro alpha no es un número válido.");
        double[] data1 = getData(results1);
        double[] data2 = getData(results2);


        if(testOption.equals("t")) {
            TTest test = new TTest();
            double pValue = test.pairedTTest(data1, data2);
            printVeredict(pValue, alpha);
        }
        else if(testOption.equals("wilcoxon")) {
            WilcoxonSignedRankTest test = new WilcoxonSignedRankTest();
            try {
                double pValue = test.wilcoxonSignedRankTest(data1, data2, false);
                printVeredict(pValue, alpha);
            }  catch (NullArgumentException | NoDataException e) {
                System.err.println("No hay datos en los archivos de resultados: " + e.getMessage());
                System.exit(1);
            } catch (ConvergenceException e) {
                System.err.println("No se puede calcular el p-value por un error de convergencia: " + e.getMessage());
                System.exit(1);
            } catch (MaxCountExceededException e) {
                System.err.println("Número máximo de iteraciones sobrepasado en el test: " + e.getMessage());
                System.exit(1);
            }


        }

    }

    private static void printVeredict(double pValue, double alpha) {
        System.out.println("P-value: " + pValue);
        if(pValue < alpha)
            System.out.println("Como el p-value es menor, la hipótesis nula puede ser rechazada.");
        else
            System.out.println("Como el p-value es mayor, la hipótesis nula no se rechaza.");
    }

    private static double[] getData(String path) {
        File file = new File(path);
        List<Double> lista = new LinkedList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();      // ignoramos la línea de cabecera
            while ((line = reader.readLine()) != null) {
                String[] tmp = line.split(",");
                lista.add(tryParseDouble(tmp[1], "Los datos del archivo " + path + " no son válidos."));
            }

            // meter la lista en un double[]
            double[] acc = new double[lista.size()];
            int i = 0;
            for (double elem : lista) {
                acc[i] = elem;
                i++;
            }
            return acc;
        } catch (IOException e) {
            System.err.println("Error de E/S al leer el archivo " + path + ": " + e.getMessage());
            System.exit(1);
        }


        return null;
    }

    private static double tryParseDouble(String n, String errMsg) {
        try {
            return Double.parseDouble(n);
        } catch (NumberFormatException e) {
            System.err.println("Error de parsing: " + errMsg);
            System.exit(1);
        }
        return 0;
    }
}
