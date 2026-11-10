package dev.marketplace.cataloginventory.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * Leitor CSV da RFC 4180: vírgula como separador, campo entre aspas com aspas
 * duplicadas como escape e quebra de linha (CRLF, LF ou CR) dentro de aspas.
 * Linhas totalmente vazias são ignoradas; sem reflexão, para a imagem nativa.
 */
public final class CsvParser {
  /** Registro com a linha física onde começa (a primeira é 1) e os campos. */
  public record Record(int line, List<String> fields) {
  }

  /** Sintaxe CSV inválida; {@code line} é a linha física do problema. */
  public static final class SyntaxException extends RuntimeException {
    private final int line;

    SyntaxException(int line, String message) {
      super("Linha " + line + ": " + message);
      this.line = line;
    }

    public int line() {
      return line;
    }
  }

  private final String text;
  private int pos;
  private int line = 1;

  private CsvParser(String text) {
    this.text = text;
  }

  /**
   * Lê todos os registros do texto. Aspas sem fechamento, aspas dentro de
   * campo sem aspas ou texto após as aspas de fechamento lançam
   * {@link SyntaxException}.
   */
  public static List<Record> parse(String text) {
    return new CsvParser(text).records();
  }

  private List<Record> records() {
    List<Record> records = new ArrayList<>();
    while (pos < text.length()) {
      int start = line;
      List<String> fields = new ArrayList<>();
      boolean blank = true;
      while (true) {
        boolean quoted = pos < text.length() && text.charAt(pos) == '"';
        String value = quoted ? quotedField() : plainField();
        blank &= !quoted && value.isEmpty();
        fields.add(value);
        if (pos >= text.length())
          break;
        if (text.charAt(pos) == ',') {
          pos++;
          blank = false;
          continue;
        }
        newline();
        break;
      }
      if (!blank)
        records.add(new Record(start, List.copyOf(fields)));
    }
    return records;
  }

  private String plainField() {
    int begin = pos;
    while (pos < text.length()) {
      char c = text.charAt(pos);
      if (c == ',' || c == '\n' || c == '\r')
        break;
      if (c == '"')
        throw new SyntaxException(line, "aspas em campo que não começa com aspas");
      pos++;
    }
    return text.substring(begin, pos);
  }

  private String quotedField() {
    int opened = line;
    pos++;
    StringBuilder value = new StringBuilder();
    while (pos < text.length()) {
      char c = text.charAt(pos++);
      if (c == '"') {
        if (pos < text.length() && text.charAt(pos) == '"') {
          value.append('"');
          pos++;
          continue;
        }
        if (pos < text.length() && !isDelimiter(text.charAt(pos)))
          throw new SyntaxException(line, "texto depois das aspas de fechamento");
        return value.toString();
      }
      if (c == '\n' || c == '\r' && (pos >= text.length() || text.charAt(pos) != '\n'))
        line++;
      value.append(c);
    }
    throw new SyntaxException(opened, "aspas sem fechamento");
  }

  private void newline() {
    boolean crlf = text.charAt(pos) == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n';
    pos += crlf ? 2 : 1;
    line++;
  }

  private static boolean isDelimiter(char c) {
    return c == ',' || c == '\n' || c == '\r';
  }
}
