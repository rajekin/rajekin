

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class MultiFieldJsonUpdater {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {

        String jsonTemplatePath = "template.json";
        String excelPath = "data.xlsx";

        // Fields YOU want to update from Excel
        List<String> fieldsToUpdate = Arrays.asList("firstName", "creditScore", "loanAmount");

        updateJsonFromExcel(jsonTemplatePath, excelPath, fieldsToUpdate);
    }

    public static void updateJsonFromExcel(String jsonFile,
                                           String excelFile,
                                           List<String> fieldsToUpdate) throws Exception {

        // 1. Read JSON template as string
        String jsonString = Files.readString(Paths.get(jsonFile));
        JsonNode jsonNode = mapper.readTree(jsonString);
        ObjectNode jsonRoot = (ObjectNode) jsonNode;

        // 2. Load Excel
        try (FileInputStream fis = new FileInputStream(excelFile);
             Workbook workbook = new XSSFWorkbook(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);

            // Map of columnName → columnIndex
            Map<String, Integer> columnIndexMap = new HashMap<>();

            for (int i = 0; i < header.getPhysicalNumberOfCells(); i++) {
                String colName = header.getCell(i).getStringCellValue().trim();
                columnIndexMap.put(colName.toLowerCase(), i);
            }

            // Validate required columns exist
            for (String field : fieldsToUpdate) {
                if (!columnIndexMap.containsKey(field.toLowerCase())) {
                    throw new RuntimeException("Column '" + field + "' not found in Excel!");
                }
            }

            // 3. Iterate Excel rows
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {

                Row row = sheet.getRow(r);
                if (row == null) continue;

                // Make a fresh copy of template for each row
                ObjectNode updatedJson = jsonRoot.deepCopy();

                // 4. Loop over all fields to update
                for (String field : fieldsToUpdate) {

                    int colIndex = columnIndexMap.get(field.toLowerCase());
                    Cell cell = row.getCell(colIndex);
                    if (cell == null) continue;

                    String newValue = getCellValueAsString(cell);

                    // Update the JSON
                    updatedJson.put(field, newValue);
                }

                // 5. Convert to pretty JSON string
                String updatedJsonString =
                        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(updatedJson);

                System.out.println("\n===== JSON for Row " + r + " =====");
                System.out.println(updatedJsonString);

                // 6. Optional: Save each output file
                Files.write(
                        Paths.get("output_row_" + r + ".json"),
                        updatedJsonString.getBytes()
                );
            }
        }
    }

    private static String getCellValueAsString(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }
}
