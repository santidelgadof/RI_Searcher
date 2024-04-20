package practicari;
public class Compare {
    private static final String usage = "Uso: -test t | wilcoxon <alpha> -results <results1.csv> <results2.csv>\n" +
            "  -test t | wilcoxon <alpha>: test de significancia estadística (t-test o Wilcoxon) y nivel de " +
            "significancia alpha.\n" +
            "  -results <results1.csv> <results2.csv>: results1 y results2 son archivos de resultados " +
            "obtenidos con TrainingTestTrecCovid para la misma métrica y sobre las mismas queries de " +
            "test.\n";
    public static void main(String[] args) {
        String test = null;
        String signif = null;
        double alpha;
        String results1 = null, results2 = null;


        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-test":
                    test = args[++i];
                    signif = args[++i];
                    break;
                case "-results":
                    results1 = args[++i];
                    results2 = args[++i];
                    break;
                default:
                    System.out.println("Parámetro desconocido: " + args[i]);
                    System.out.println(usage);
                    return;
            }
        }

        if(test == null || signif == null) {
            System.out.println("El argumento \"test\" y sus parámetros son obligatorios.");
            System.exit(-1);
        } else if(results1 == null || results2 == null) {
            System.out.println("El argumento \"result\" y sus parámetros son obligatorios.");
            System.exit(-1);
        }

        // parsear signif en alpha
        alpha = Double.parseDouble(signif);



    }

    public static Double parseDouble(String signif) {
        try {
            return Double.parseDouble(signif);
        } catch (NumberFormatException e) {
            System.err.println("Error al parsear alpha \"" + signif + "\": " + e.getMessage());
            System.exit(-1);
        }
        return 0.0;

    }
}
