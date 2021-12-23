package com.onthegomap.planetiler.basemap;

import static com.onthegomap.planetiler.expression.Expression.*;
import static java.util.stream.Collectors.joining;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.CaseFormat;
import com.onthegomap.planetiler.config.Arguments;
import com.onthegomap.planetiler.config.PlanetilerConfig;
import com.onthegomap.planetiler.expression.Expression;
import com.onthegomap.planetiler.expression.MultiExpression;
import com.onthegomap.planetiler.util.Downloader;
import com.onthegomap.planetiler.util.FileUtils;
import com.onthegomap.planetiler.util.Format;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * Generates code in the {@code generated} package from the OpenMapTiles schema crawled from a tag or branch in the <a
 * href="https://github.com/openmaptiles/openmaptiles">OpenMapTiles GitHub repo</a>.
 * <p>
 * {@code OpenMapTilesSchema.java} contains the output layer definitions (i.e. attributes and allowed values) so that
 * layer implementations in {@code layers} package can reference them instead of hard-coding.
 * <p>
 * {@code Tables.java} contains the <a href="https://github.com/omniscale/imposm3">imposm3</a> table definitions from
 * mapping.yaml files in the OpenMapTiles repo.  Layers in the {@code layer} package can extend the {@code Handler}
 * nested class for a table definition to "subscribe" to OSM elements that imposm3 would put in that table.
 * <p>
 * To run use {@code ./scripts/regenerate-openmaptiles.sh}
 */
public class Generate {

  private static final Logger LOGGER = LoggerFactory.getLogger(Generate.class);
  private static final ObjectMapper mapper = new ObjectMapper();
  private static final Yaml yaml;
  private static final String LINE_SEPARATOR = System.lineSeparator();
  private static final String GENERATED_FILE_HEADER = """
    /*
    Copyright (c) 2016, KlokanTech.com & OpenMapTiles contributors.
    All rights reserved.

    Code license: BSD 3-Clause License

    Redistribution and use in source and binary forms, with or without
    modification, are permitted provided that the following conditions are met:

    * Redistributions of source code must retain the above copyright notice, this
      list of conditions and the following disclaimer.

    * Redistributions in binary form must reproduce the above copyright notice,
      this list of conditions and the following disclaimer in the documentation
      and/or other materials provided with the distribution.

    * Neither the name of the copyright holder nor the names of its
      contributors may be used to endorse or promote products derived from
      this software without specific prior written permission.

    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
    AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
    IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
    DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
    FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
    DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
    SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
    CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
    OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
    OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

    Design license: CC-BY 4.0

    See https://github.com/openmaptiles/openmaptiles/blob/master/LICENSE.md for details on usage
    */
    // AUTOGENERATED BY Generate.java -- DO NOT MODIFY
    """;
  private static final Parser parser = Parser.builder().build();
  private static final HtmlRenderer renderer = HtmlRenderer.builder().build();

  static {
    // bump the default limit of 50
    var options = new LoaderOptions();
    options.setMaxAliasesForCollections(1_000);
    yaml = new Yaml(options);
  }

  private static <T> T loadAndParseYaml(String url, PlanetilerConfig config, Class<T> clazz) throws IOException {
    LOGGER.info("reading " + url);
    try (var stream = Downloader.openStream(url, config)) {
      // Jackson yaml parsing does not handle anchors and references, so first parse the input
      // using SnakeYAML, then parse SnakeYAML's output using Jackson to get it into our records.
      Map<String, Object> parsed = yaml.load(stream);
      return mapper.convertValue(parsed, clazz);
    }
  }

  static <T> T parseYaml(String string, Class<T> clazz) {
    // Jackson yaml parsing does not handle anchors and references, so first parse the input
    // using SnakeYAML, then parse SnakeYAML's output using Jackson to get it into our records.
    Map<String, Object> parsed = yaml.load(string);
    return mapper.convertValue(parsed, clazz);
  }

  static JsonNode parseYaml(String string) {
    return string == null ? null : parseYaml(string, JsonNode.class);
  }

  public static void main(String[] args) throws IOException {
    Arguments arguments = Arguments.fromArgsOrConfigFile(args);
    PlanetilerConfig planetilerConfig = PlanetilerConfig.from(arguments);
    String tag = arguments.getString("tag", "openmaptiles tag to use", "v3.12.2");
    String base = "https://raw.githubusercontent.com/openmaptiles/openmaptiles/" + tag + "/";

    // start crawling from openmaptiles.yaml
    // then crawl schema from each layers/<layer>/<layer>.yaml file that it references
    // then crawl table definitions from each layers/<layer>/mapping.yaml file that the layer references
    String rootUrl = base + "openmaptiles.yaml";
    OpenmaptilesConfig config = loadAndParseYaml(rootUrl, planetilerConfig, OpenmaptilesConfig.class);

    List<LayerConfig> layers = new ArrayList<>();
    Set<String> imposm3MappingFiles = new LinkedHashSet<>();
    for (String layerFile : config.tileset.layers) {
      String layerURL = base + layerFile;
      LayerConfig layer = loadAndParseYaml(layerURL, planetilerConfig, LayerConfig.class);
      layers.add(layer);
      for (Datasource datasource : layer.datasources) {
        if ("imposm3".equals(datasource.type)) {
          String mappingPath = Path.of(layerFile).resolveSibling(datasource.mapping_file).normalize().toString();
          imposm3MappingFiles.add(base + mappingPath);
        } else {
          LOGGER.warn("Unknown datasource type: " + datasource.type);
        }
      }
    }

    Map<String, Imposm3Table> tables = new LinkedHashMap<>();
    for (String uri : imposm3MappingFiles) {
      Imposm3Mapping layer = loadAndParseYaml(uri, planetilerConfig, Imposm3Mapping.class);
      tables.putAll(layer.tables);
    }

    String packageName = "com.onthegomap.planetiler.basemap.generated";
    String[] packageParts = packageName.split("\\.");
    Path output = Path.of("planetiler-basemap", "src", "main", "java")
      .resolve(Path.of(packageParts[0], Arrays.copyOfRange(packageParts, 1, packageParts.length)));

    FileUtils.deleteDirectory(output);
    Files.createDirectories(output);

    emitLayerSchemaDefinitions(config.tileset, layers, packageName, output, tag);
    emitTableDefinitions(tables, packageName, output, tag);
    LOGGER.info(
      "Done generating code in 'generated' package, now run IntelliJ 'Reformat Code' operation with 'Optimize imports' and 'Cleanup code' options selected.");
  }

  /** Generates {@code OpenMapTilesSchema.java} */
  private static void emitLayerSchemaDefinitions(OpenmaptilesTileSet info, List<LayerConfig> layers, String packageName,
    Path output, String tag)
    throws IOException {
    StringBuilder schemaClass = new StringBuilder();
    schemaClass.append("""
      %s
      package %s;

      import static com.onthegomap.planetiler.expression.Expression.*;
      import com.onthegomap.planetiler.config.PlanetilerConfig;
      import com.onthegomap.planetiler.stats.Stats;
      import com.onthegomap.planetiler.expression.MultiExpression;
      import com.onthegomap.planetiler.basemap.Layer;
      import com.onthegomap.planetiler.util.Translations;
      import java.util.List;
      import java.util.Map;
      import java.util.Set;

      /**
       * All vector tile layer definitions, attributes, and allowed values generated from the
       * <a href="https://github.com/openmaptiles/openmaptiles/blob/%s/openmaptiles.yaml">OpenMapTiles vector tile schema %s</a>.
       */
      @SuppressWarnings("unused")
      public class OpenMapTilesSchema {
        public static final String NAME = %s;
        public static final String DESCRIPTION = %s;
        public static final String VERSION = %s;
        public static final String ATTRIBUTION = %s;
        public static final List<String> LANGUAGES = List.of(%s);

        /** Returns a list of expected layer implementation instances from the {@code layers} package. */
        public static List<Layer> createInstances(Translations translations, PlanetilerConfig config, Stats stats) {
          return List.of(
            %s
          );
        }
      """
      .formatted(
        GENERATED_FILE_HEADER,
        packageName,
        escapeJavadoc(tag),
        escapeJavadoc(tag),
        Format.quote(info.name),
        Format.quote(info.description),
        Format.quote(info.version),
        Format.quote(info.attribution),
        info.languages.stream().map(Format::quote).collect(joining(", ")),
        layers.stream()
          .map(
            l -> "new com.onthegomap.planetiler.basemap.layers.%s(translations, config, stats)"
              .formatted(lowerUnderscoreToUpperCamel(l.layer.id)))
          .collect(joining("," + LINE_SEPARATOR))
          .indent(6).trim()
      ));
    for (var layer : layers) {
      String layerCode = generateCodeForLayer(tag, layer);
      schemaClass.append(layerCode);
    }

    schemaClass.append("}");
    Files.writeString(output.resolve("OpenMapTilesSchema.java"), schemaClass);
  }

  private static String generateCodeForLayer(String tag, LayerConfig layer) {
    String layerName = layer.layer.id;
    String className = lowerUnderscoreToUpperCamel(layerName);

    StringBuilder fields = new StringBuilder();
    StringBuilder fieldValues = new StringBuilder();
    StringBuilder fieldMappings = new StringBuilder();

    layer.layer.fields.forEach((name, value) -> {
      JsonNode valuesNode = value.get("values");
      List<String> valuesForComment = valuesNode == null ? List.of() : valuesNode.isArray() ?
        iterToList(valuesNode.elements()).stream().map(Objects::toString).toList() :
        iterToList(valuesNode.fieldNames());
      String javadocDescription = markdownToJavadoc(getFieldDescription(value));
      fields.append("""
        %s
        public static final String %s = %s;
        """.formatted(
        valuesForComment.isEmpty() ? "/** %s */".formatted(javadocDescription) : """

          /**
           * %s
           * <p>
           * allowed values:
           * <ul>
           * %s
           * </ul>
           */
          """.stripTrailing().formatted(javadocDescription,
          valuesForComment.stream().map(v -> "<li>" + v).collect(joining(LINE_SEPARATOR + " * "))),
        name.toUpperCase(Locale.ROOT),
        Format.quote(name)
      ).indent(4));

      List<String> values = valuesNode == null ? List.of() : valuesNode.isArray() ?
        iterToList(valuesNode.elements()).stream().filter(JsonNode::isTextual).map(JsonNode::textValue)
          .map(t -> t.replaceAll(" .*", "")).toList() :
        iterToList(valuesNode.fieldNames());
      if (values.size() > 0) {
        fieldValues.append(values.stream()
          .map(v -> "public static final String %s = %s;"
            .formatted(name.toUpperCase(Locale.ROOT) + "_" + v.toUpperCase(Locale.ROOT).replace('-', '_'),
              Format.quote(v)))
          .collect(joining(LINE_SEPARATOR)).indent(2).strip()
          .indent(4));
        fieldValues.append("public static final Set<String> %s = Set.of(%s);".formatted(
          name.toUpperCase(Locale.ROOT) + "_VALUES",
          values.stream().map(Format::quote).collect(joining(", "))
        ).indent(4));
      }

      if (valuesNode != null && valuesNode.isObject()) {
        MultiExpression<String> mapping = generateFieldMapping(valuesNode);
        fieldMappings.append("    public static final MultiExpression<String> %s = %s;%n"
          .formatted(lowerUnderscoreToUpperCamel(name), generateJavaCode(mapping)));
      }
    });

    return """
      /**
       * %s
       *
       * Generated from <a href="https://github.com/openmaptiles/openmaptiles/blob/%s/layers/%s/%s.yaml">%s.yaml</a>
       */
      public interface %s extends Layer {
        double BUFFER_SIZE = %s;
        String LAYER_NAME = %s;
        @Override
        default String name() {
          return LAYER_NAME;
        }
        /** Attribute names for map elements in the %s layer. */
        final class Fields {
          %s
        }
        /** Attribute values for map elements in the %s layer. */
        final class FieldValues {
          %s
        }
        /** Complex mappings to generate attribute values from OSM element tags in the %s layer. */
        final class FieldMappings {
          %s
        }
      }
      """.formatted(
      markdownToJavadoc(layer.layer.description),
      escapeJavadoc(tag),
      escapeJavadoc(layerName),
      escapeJavadoc(layerName),
      escapeJavadoc(layerName),
      className,
      layer.layer.buffer_size,
      Format.quote(layerName),
      escapeJavadoc(layerName),
      fields.toString().strip(),
      escapeJavadoc(layerName),
      fieldValues.toString().strip(),
      escapeJavadoc(layerName),
      fieldMappings.toString().strip()
    ).indent(2);
  }

  /** Generates {@code Tables.java} */
  private static void emitTableDefinitions(Map<String, Imposm3Table> tables, String packageName, Path output,
    String tag)
    throws IOException {
    StringBuilder tablesClass = new StringBuilder();
    tablesClass.append("""
      %s
      package %s;

      import static com.onthegomap.planetiler.expression.Expression.*;

      import com.onthegomap.planetiler.expression.Expression;
      import com.onthegomap.planetiler.expression.MultiExpression;
      import com.onthegomap.planetiler.FeatureCollector;
      import com.onthegomap.planetiler.reader.SourceFeature;
      import java.util.ArrayList;
      import java.util.HashMap;
      import java.util.HashSet;
      import java.util.List;
      import java.util.Map;
      import java.util.Set;

      /**
       * OSM element parsers generated from the <a href="https://github.com/omniscale/imposm3">imposm3</a> table definitions
       * in the <a href="https://github.com/openmaptiles/openmaptiles/blob/%s/openmaptiles.yaml">OpenMapTiles vector tile schema</a>.
       *
       * These filter and parse the raw OSM key/value attribute pairs on tags into records with fields that match the
       * columns in the tables that imposm3 would generate.  Layer implementations can "subscribe" to elements from each
       * "table" but implementing the table's {@code Handler} interface and use the element's typed API to access
       * attributes.
       */
      @SuppressWarnings("unused")
      public class Tables {
          /** A parsed OSM element that would appear in a "row" of the imposm3 table. */
          public interface Row {

            /** Returns the original OSM element. */
            SourceFeature source();
          }

          /** A functional interface that the constructor of a new table row can be coerced to. */
          @FunctionalInterface
          public interface Constructor {

            Row create(SourceFeature source, String mappingKey);
          }

          /** The {@code rowClass} of an imposm3 table row and its constructor coerced to a {@link Constructor}. */
          public static record RowClassAndConstructor(
            Class<? extends Row> rowClass,
            Constructor create
          ) {}

          /** A functional interface that the typed handler method that a layer implementation can be coerced to. */
          @FunctionalInterface
          public interface RowHandler<T extends Row> {

            /** Process a typed element according to the profile. */
            void process(T element, FeatureCollector features);
          }

          /** The {@code handlerClass} of a layer handler and it's {@code process} method coerced to a {@link RowHandler}. */
          public static record RowHandlerAndClass<T extends Row>(
            Class<?> handlerClass,
            RowHandler<T> handler
          ) {}
      """.formatted(GENERATED_FILE_HEADER, packageName, escapeJavadoc(tag)));

    List<String> classNames = new ArrayList<>();
    Map<String, String> fieldNameToType = new TreeMap<>();
    for (var entry : tables.entrySet()) {
      String key = entry.getKey();
      Imposm3Table table = entry.getValue();
      List<OsmTableField> fields = parseTableFields(table);
      for (var field : fields) {
        String existing = fieldNameToType.get(field.name);
        if (existing == null) {
          fieldNameToType.put(field.name, field.clazz);
        } else if (!existing.equals(field.clazz)) {
          throw new IllegalArgumentException(
            "Field " + field.name + " has both " + existing + " and " + field.clazz + " types");
        }
      }
      Expression mappingExpression = parseImposm3MappingExpression(table);
      String mapping = """
        /** Imposm3 "mapping" to filter OSM elements that should appear in this "table". */
        public static final Expression MAPPING = %s;
        """.formatted(
        mappingExpression
      );
      String tableName = "osm_" + key;
      String className = lowerUnderscoreToUpperCamel(tableName);
      if (!"relation_member".equals(table.type)) {
        classNames.add(className);

        tablesClass.append("""
          /** An OSM element that would appear in the {@code %s} table generated by imposm3. */
          public static record %s(%s) implements Row, %s {
            public %s(SourceFeature source, String mappingKey) {
              this(%s);
            }
            %s
            /**
             * Interface for layer implementations to extend to subscribe to OSM elements filtered and parsed as
             * {@link %s}.
             */
            public interface Handler {
              void process(%s element, FeatureCollector features);
            }
          }
          """.formatted(
          tableName,
          escapeJavadoc(className),
          fields.stream().map(c -> "@Override " + c.clazz + " " + lowerUnderscoreToLowerCamel(c.name))
            .collect(joining(", ")),
          fields.stream().map(c -> lowerUnderscoreToUpperCamel("with_" + c.name))
            .collect(joining(", ")),
          className,
          fields.stream().map(c -> c.extractCode).collect(joining(", ")),
          mapping,
          escapeJavadoc(className),
          className
        ).indent(2));
      }
    }

    tablesClass.append(fieldNameToType.entrySet().stream().map(e -> {
      String attrName = lowerUnderscoreToLowerCamel(e.getKey());
      String type = e.getValue();
      String interfaceName = lowerUnderscoreToUpperCamel("with_" + e.getKey());
      return """
        /** Rows with a %s %s attribute. */
        public interface %s {
          %s %s();
        }
        """.formatted(
        escapeJavadoc(type),
        escapeJavadoc(attrName),
        interfaceName,
        type,
        attrName);
    }).collect(joining(LINE_SEPARATOR)).indent(2));

    tablesClass.append("""
      /** Index to efficiently choose which imposm3 "tables" an element should appear in based on its attributes. */
      public static final MultiExpression<RowClassAndConstructor> MAPPINGS = MultiExpression.of(List.of(
        %s
      ));
      """.formatted(
      classNames.stream().map(
          className -> "MultiExpression.entry(new RowClassAndConstructor(%s.class, %s::new), %s.MAPPING)".formatted(
            className, className, className))
        .collect(joining("," + LINE_SEPARATOR)).indent(2).strip()
    ).indent(2));

    String handlerCondition = classNames.stream().map(className ->
      """
        if (handler instanceof %s.Handler typedHandler) {
          result.computeIfAbsent(%s.class, cls -> new ArrayList<>()).add(new RowHandlerAndClass<>(typedHandler.getClass(), typedHandler::process));
        }""".formatted(className, className)
    ).collect(joining(LINE_SEPARATOR));
    tablesClass.append("""
        /**
         * Returns a map from imposm3 "table row" class to the layers that have a handler for it from a list of layer
         * implementations.
         */
        public static Map<Class<? extends Row>, List<RowHandlerAndClass<?>>> generateDispatchMap(List<?> handlers) {
          Map<Class<? extends Row>, List<RowHandlerAndClass<?>>> result = new HashMap<>();
          for (var handler : handlers) {
            %s
          }
          return result;
        }
      }
      """.formatted(handlerCondition.indent(6).trim()));
    Files.writeString(output.resolve("Tables.java"), tablesClass);
  }

  /**
   * Returns an {@link Expression} that implements the same logic as the <a href="https://imposm.org/docs/imposm3/latest/mapping.html">Imposm3
   * Data Mapping</a> definition for a table.
   */
  static Expression parseImposm3MappingExpression(Imposm3Table table) {
    if (table.type_mappings != null) {
      return or(
        table.type_mappings.entrySet().stream().map(entry ->
          parseImposm3MappingExpression(entry.getKey(), entry.getValue(), table.filters)
        ).toList()
      ).simplify();
    } else {
      return parseImposm3MappingExpression(table.type, table.mapping, table.filters);
    }
  }

  /**
   * Returns an {@link Expression} that implements the same logic as the <a href="https://imposm.org/docs/imposm3/latest/mapping.html#filters">Imposm3
   * Data Mapping filters</a> for a table.
   */
  static Expression parseImposm3MappingExpression(String type, JsonNode mapping, Imposm3Filters filters) {
    return and(
      or(parseFieldMappingExpression(mapping).toList()),
      and(
        filters == null || filters.require == null ? List.of() : parseFieldMappingExpression(filters.require).toList()),
      not(or(
        filters == null || filters.reject == null ? List.of() : parseFieldMappingExpression(filters.reject).toList())),
      matchType(type.replaceAll("s$", ""))
    ).simplify();
  }

  private static List<OsmTableField> parseTableFields(Imposm3Table tableDefinition) {
    List<OsmTableField> result = new ArrayList<>();
    boolean relationMember = "relation_member".equals(tableDefinition.type);
    for (Imposm3Column col : tableDefinition.columns) {
      if (relationMember && col.from_member) {
        // layers process relation info that they need manually
        continue;
      }
      switch (col.type) {
        case "id", "validated_geometry", "area", "hstore_tags", "geometry" -> {
          // do nothing - already on source feature
        }
        case "member_id", "member_role", "member_type", "member_index" -> {
          // do nothing
        }
        case "mapping_key" -> result
          .add(new OsmTableField("String", col.name, "mappingKey"));
        case "mapping_value" -> result
          .add(new OsmTableField("String", col.name, "source.getString(mappingKey)"));
        case "string" -> result
          .add(new OsmTableField("String", col.name,
            "source.getString(\"%s\")".formatted(Objects.requireNonNull(col.key, col.toString()))));
        case "bool" -> result
          .add(new OsmTableField("boolean", col.name,
            "source.getBoolean(\"%s\")".formatted(Objects.requireNonNull(col.key, col.toString()))));
        case "integer" -> result
          .add(new OsmTableField("long", col.name,
            "source.getLong(\"%s\")".formatted(Objects.requireNonNull(col.key, col.toString()))));
        case "wayzorder" -> result.add(new OsmTableField("int", col.name, "source.getWayZorder()"));
        case "direction" -> result.add(new OsmTableField("int", col.name,
          "source.getDirection(\"%s\")".formatted(Objects.requireNonNull(col.key, col.toString()))));
        default -> throw new IllegalArgumentException("Unhandled column: " + col.type);
      }
    }
    result.add(new OsmTableField("SourceFeature", "source", "source"));
    return result;
  }

  /**
   * Returns a {@link MultiExpression} to efficiently determine the value for an output vector tile feature (i.e.
   * "class") based on the "field mapping" defined in the layer schema definition.
   */
  static MultiExpression<String> generateFieldMapping(JsonNode valuesNode) {
    MultiExpression<String> mapping = MultiExpression.of(new ArrayList<>());
    valuesNode.fields().forEachRemaining(entry -> {
      String field = entry.getKey();
      JsonNode node = entry.getValue();
      Expression expression = or(parseFieldMappingExpression(node).toList()).simplify();
      if (!expression.equals(or()) && !expression.equals(and())) {
        mapping.expressions().add(MultiExpression.entry(field, expression));
      }
    });
    return mapping;
  }

  private static Stream<Expression> parseFieldMappingExpression(JsonNode node) {
    if (node.isObject()) {
      List<String> keys = iterToList(node.fieldNames());
      if (keys.contains("__AND__")) {
        if (keys.size() > 1) {
          throw new IllegalArgumentException("Cannot combine __AND__ with others");
        }
        return Stream.of(and(parseFieldMappingExpression(node.get("__AND__")).toList()));
      } else if (keys.contains("__OR__")) {
        if (keys.size() > 1) {
          throw new IllegalArgumentException("Cannot combine __OR__ with others");
        }
        return Stream.of(or(parseFieldMappingExpression(node.get("__OR__")).toList()));
      } else {
        return iterToList(node.fields()).stream().map(entry -> {
          String field = entry.getKey();
          List<String> value = toFlatList(entry.getValue()).map(JsonNode::textValue).filter(Objects::nonNull).toList();
          return value.isEmpty() || value.contains("__any__") ? matchField(field) : matchAny(field, value);
        });
      }
    } else if (node.isArray()) {
      return iterToList(node.elements()).stream().flatMap(Generate::parseFieldMappingExpression);
    } else if (node.isNull()) {
      return Stream.empty();
    } else {
      throw new IllegalArgumentException("parseExpression input not handled: " + node);
    }
  }

  /**
   * Returns a flattened list of all the elements in nested arrays from {@code node}.
   * <p>
   * For example: {@code [[[a, b], c], [d]} becomes {@code [a, b, c, d]}
   * <p>
   * And {@code a} becomes {@code [a]}
   */
  private static Stream<JsonNode> toFlatList(JsonNode node) {
    return node.isArray() ? iterToList(node.elements()).stream().flatMap(Generate::toFlatList) : Stream.of(node);
  }

  /** Returns java code that will recreate an {@link MultiExpression} identical to {@code mapping}. */
  private static String generateJavaCode(MultiExpression<String> mapping) {
    return "MultiExpression.of(List.of(" + mapping.expressions().stream()
      .map(s -> "MultiExpression.entry(%s, %s)".formatted(Format.quote(s.result()), s.expression()))
      .collect(joining(", ")) + "))";
  }

  private static String lowerUnderscoreToLowerCamel(String name) {
    return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name);
  }

  private static String lowerUnderscoreToUpperCamel(String name) {
    return CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, name);
  }

  private static <T> List<T> iterToList(Iterator<T> iter) {
    List<T> result = new ArrayList<>();
    iter.forEachRemaining(result::add);
    return result;
  }

  /** Renders {@code markdown} as HTML and returns comment text safe to insert in generated javadoc. */
  private static String markdownToJavadoc(String markdown) {
    return Stream.of(markdown.strip().split("[\r\n][\r\n]+"))
      .map(p -> parser.parse(p.strip()))
      .map(node -> escapeJavadoc(renderer.render(node)))
      .map(p -> p.replaceAll("(^<p>|</p>$)", "").strip())
      .collect(joining(LINE_SEPARATOR + "<p>" + LINE_SEPARATOR));
  }

  /** Returns {@code comment} text safe to insert in generated javadoc. */
  private static String escapeJavadoc(String comment) {
    return comment.strip().replaceAll("[\n\r*\\s]+", " ");
  }

  private static String getFieldDescription(JsonNode value) {
    if (value.isTextual()) {
      return value.textValue();
    } else {
      return value.get("description").textValue();
    }
  }

  /*
   * Models for deserializing yaml into:
   */

  private static record OpenmaptilesConfig(
    OpenmaptilesTileSet tileset
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static record OpenmaptilesTileSet(
    List<String> layers,
    String version,
    String attribution,
    String name,
    String description,
    List<String> languages
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static record LayerDetails(
    String id,
    String description,
    Map<String, JsonNode> fields,
    double buffer_size
  ) {}

  private static record Datasource(
    String type,
    String mapping_file
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static record LayerConfig(
    LayerDetails layer,
    List<Datasource> datasources
  ) {}

  private static record Imposm3Column(
    String type,
    String name,
    String key,
    boolean from_member
  ) {}

  static record Imposm3Filters(
    JsonNode reject,
    JsonNode require
  ) {}

  static record Imposm3Table(
    String type,
    @JsonProperty("_resolve_wikidata") boolean resolveWikidata,
    List<Imposm3Column> columns,
    Imposm3Filters filters,
    JsonNode mapping,
    Map<String, JsonNode> type_mappings
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private static record Imposm3Mapping(
    Map<String, Imposm3Table> tables
  ) {}

  private static record OsmTableField(
    String clazz,
    String name,
    String extractCode
  ) {}
}
