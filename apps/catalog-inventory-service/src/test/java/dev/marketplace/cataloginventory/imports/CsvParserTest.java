package dev.marketplace.cataloginventory.imports;

import dev.marketplace.cataloginventory.imports.CsvParser.Record;
import dev.marketplace.cataloginventory.imports.CsvParser.SyntaxException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CsvParserTest {
    @Test
    void quotedFieldsKeepCommasEscapedQuotesAndLineBreaks() {
        List<Record> records = CsvParser
                .parse("a,b,c\r\n\"x, y\",\"diz \"\"oi\"\"\",\"linha 1\r\nlinha 2\"\r\nfim,,\n");
        assertEquals(List.of(new Record(1, List.of("a", "b", "c")),
                new Record(2, List.of("x, y", "diz \"oi\"", "linha 1\r\nlinha 2")),
                new Record(4, List.of("fim", "", ""))), records);
    }

    @Test
    void blankLinesAreSkippedButCountInTheLineNumbers() {
        assertEquals(List.of(new Record(1, List.of("a")), new Record(3, List.of("b"))),
                CsvParser.parse("a\n\nb\n\n\r\n"));
        assertEquals(List.of(new Record(1, List.of("a")), new Record(2, List.of("b"))), CsvParser.parse("a\rb"));
        assertEquals(List.of(new Record(1, List.of(""))), CsvParser.parse("\"\"\n"));
        assertEquals(List.of(), CsvParser.parse(""));
    }

    @Test
    void lastRecordWithoutLineBreakIsRead() {
        assertEquals(List.of(new Record(1, List.of("a", "b")), new Record(2, List.of("c", "\"d\""))),
                CsvParser.parse("a,b\nc,\"\"\"d\"\"\""));
    }

    @Test
    void malformedQuotingReportsTheLine() {
        assertEquals(2, assertThrows(SyntaxException.class, () -> CsvParser.parse("a\n\"aberto,b\nc")).line());
        assertEquals(1, assertThrows(SyntaxException.class, () -> CsvParser.parse("ab\"c")).line());
        assertEquals(3, assertThrows(SyntaxException.class, () -> CsvParser.parse("a\n\"x\ny\"z")).line());
    }
}
