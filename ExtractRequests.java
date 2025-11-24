<dependency>
    <groupId>org.jpmml</groupId>
    <artifactId>pmml-lightgbm</artifactId>
    <version>1.6.2</version> <!-- or the latest available -->
</dependency>

<dependency>
    <groupId>org.jpmml</groupId>
    <artifactId>pmml-lightgbm-example</artifactId>
    <version>1.6.2</version>
</dependency>

    public class LgbmToPmmlConverter {

    /**
     * Programmatic API.
     *
     * @param lgbmModelPath   Path to LightGBM text model (model_lgbm.txt)
     * @param pmmlOutputPath  Path to write PMML (model_lgbm.pmml)
     */
    public static void convert(String lgbmModelPath, String pmmlOutputPath) {
        String[] args = new String[] {
                "--lgbm-input", lgbmModelPath,
                "--pmml-output", pmmlOutputPath
        };

        // Call the official JPMML LightGBM example main
        org.jpmml.lightgbm.example.Main.main(args);
    }

    /**
     * Optional CLI wrapper:
     *   java com.techsavants.lgbm.LgbmToPmmlConverter in.txt out.pmml
     */
    public static void main(String[] args) {
        if (args.length != 2) {
            System.err.println("Usage: LgbmToPmmlConverter <lgbm-input.txt> <pmml-output.pmml>");
            System.exit(1);
        }

        String lgbmModelPath = args[0];
        String pmmlOutputPath = args[1];

        convert(lgbmModelPath, pmmlOutputPath);

        System.out.println("Converted LightGBM model:");
        System.out.println("  Input : " + lgbmModelPath);
        System.out.println("  Output: " + pmmlOutputPath);
    }
}
