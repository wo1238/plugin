package com.example.pgat

/**
 * Plantillas por tipo de test.
 *
 *  - HEADER:  molde fijo. {{PACKAGE}}, {{TEST_CLASS_NAME}}, {{SOURCE_NAME}} y {{CONFIG_PATH}}
 *             se reemplazan en Kotlin (TestPromptBuilder) ANTES de llamar a la IA.
 *  - PATTERN: lo que repite la IA por cada método. Lo que va entre < > lo completa la IA con datos
 *             reales; lo que va entre {{ }} lo reemplaza el sistema con valores MEDIDOS.
 */
object TestTemplates {

    // ============================================================
    // PARAMETRY: object con constantes (val). No pasa por la IA salvo casos ambiguos.
    // ============================================================

    val PARAMETRY_HEADER = """
        package {{PACKAGE}}

        import com.bbva.co.csan.ContextProvider
        import org.scalatest.{FlatSpec, Matchers}

        class {{TEST_CLASS_NAME}} extends FlatSpec with Matchers with ContextProvider {

          "The values of {{SOURCE_NAME}}" should "be correct for all parameters" in {
        {{ASSERTS}}
          }

          "When getting {{SOURCE_NAME}}" should "not be null" in {
            {{SOURCE_NAME}} shouldNot be(null)
          }
        }
    """.trimIndent()


    // ============================================================
    // GET DATA
    // ============================================================

    val GET_DATA_HEADER = """
        package {{PACKAGE}}

        import com.bbva.co.csan.ContextProvider
        import com.datio.dataproc.sdk.datiosparksession.DatioSparkSession
        import com.typesafe.config.{Config, ConfigFactory}
        import org.apache.spark.sql.DataFrame
        import org.scalatest.{FlatSpec, Matchers}
        import java.io.File
        // Mantén además cualquier otro import que el código fuente ya use
        // (por ejemplo, el object de Parametry asociado, si lo importa).

        class {{TEST_CLASS_NAME}} extends FlatSpec with Matchers with ContextProvider {

          lazy val sparkDatio: DatioSparkSession = DatioSparkSession.getOrCreate()
          val config: Config = ConfigFactory.parseFile(new File("{{CONFIG_PATH}}"))

          def getObjectProcess: {{SOURCE_NAME}} = new {{SOURCE_NAME}}(sparkDatio.getSparkSession, config)

          // AQUÍ VAN LOS TESTS: uno por cada método de la lista.
        }
    """.trimIndent()

    val GET_DATA_PATTERN = """
        Escribe UN bloque de test por cada método de la lista, en el orden en que aparecen en el código.

        PARTES PARAMETRIZABLES DE CADA BLOQUE:
          - número:           {{N}}   (escríbelo literal, el sistema numera)
          - método:           <METODO>   (nombre EXACTO del código)
          - argumentos:       <ARGUMENTOS>   (las mismas constantes que usa el código al llamarlo, en el orden de la firma)
          - variable:         dfInputs (para el Map) o evaluate (para un DataFrame)
          - clave de sonda:   <CLAVE>   (la clave que pusiste en tu @@PROBE; normalmente el nombre del método)
          - filas/columnas:   {{ROWS:<CLAVE>}} y {{COLS:<CLAVE>}}
          - tamaño del Map:   {{MAPSIZE:<METODO>}}
          - excepción:        <EXCEPCION> y <MENSAJE_ESPERADO> (tomados del código, nunca inventados)
          - condición:        <CONDICION_REAL> (en inglés, lo que realmente hace el método)
        Reemplaza cada < > por el dato real; deja los {{ }} con la clave real dentro, por ejemplo {{ROWS:getDefaultMasterTable}}.

        PATRÓN A - método sin parámetros que devuelve Map[String, DataFrame]:

          "{{N}}. The test of <METODO>" should "get a Map(String, Dataframe) with {{MAPSIZE:<METODO>}} elements" in {
            val dfInputs = getObjectProcess.<METODO>
            assert(dfInputs.isInstanceOf[Map[String, DataFrame]] &&
              dfInputs.size == {{MAPSIZE:<METODO>}}
            )
          }

        PATRÓN B - método que recibe parámetros y devuelve un DataFrame:

          "{{N}}. The test of <METODO>" should "get a new dataframe with {{COLS:<CLAVE>}} columns and {{ROWS:<CLAVE>}} rows" in {
            val evaluate = getObjectProcess.<METODO>(<ARGUMENTOS>)
            assert(evaluate.isInstanceOf[DataFrame] &&
              evaluate.count() == {{ROWS:<CLAVE>}} &&
              evaluate.columns.length == {{COLS:<CLAVE>}}
            )
          }

        PATRÓN C - método que valida y lanza una excepción (dos bloques: el caso de error y el caso válido):

          "{{N}}. The test of <METODO>" should "throw <EXCEPCION> when <CONDICION_REAL>" in {
            val thrown = intercept[<EXCEPCION>] {
              getObjectProcess.<METODO>(<ARGUMENTOS_QUE_DISPARAN_EL_ERROR>)
            }
            assert(thrown.getMessage == <MENSAJE_ESPERADO>)
          }

          "{{N}}. The test of <METODO>" should "get a new dataframe with {{COLS:<CLAVE_VALIDO>}} columns and {{ROWS:<CLAVE_VALIDO>}} rows when <CONDICION_VALIDA>" in {
            val evaluate = getObjectProcess.<METODO>(<ARGUMENTOS_VALIDOS>)
            assert(evaluate.isInstanceOf[DataFrame] &&
              evaluate.count() == {{ROWS:<CLAVE_VALIDO>}} &&
              evaluate.columns.length == {{COLS:<CLAVE_VALIDO>}}
            )
          }
          (el DataFrame vacío sale de sparkDatio.getSparkSession.emptyDataFrame; el no vacío, de
           sparkDatio.read.parquet("<RUTA_RELATIVA>") con una ruta de la lista de parquet.
           En el caso válido, tu sonda mide el DataFrame que el método devuelve.)

        PATRÓN D - cualquier otro retorno (Boolean, String, Unit...): llama al método y compara con un valor derivado
        del código o de las constantes, sin escribir cantidades a mano.
    """.trimIndent()


    // ============================================================
    // GENERATE
    // ============================================================

    val GENERATE_HEADER = """
        package {{PACKAGE}}

        import com.bbva.co.csan.ContextProvider
        import com.datio.dataproc.sdk.datiosparksession.DatioSparkSession
        import com.typesafe.config.{Config, ConfigFactory}
        import org.apache.spark.sql.DataFrame
        import org.scalatest.{FlatSpec, Matchers}
        import java.io.File
        // Mantén además cualquier otro import que el código fuente ya use
        // (por ejemplo, el object de Parametry asociado y la clase GetData que arma el Map de entradas).

        class {{TEST_CLASS_NAME}} extends FlatSpec with Matchers with ContextProvider {

          lazy val sparkDatio: DatioSparkSession = DatioSparkSession.getOrCreate()
          val config: Config = ConfigFactory.parseFile(new File("{{CONFIG_PATH}}"))

          // AQUÍ VAN LOS TESTS: uno para el método principal (normalmente "apply")
          // y uno por cada método de transformación de la lista.
        }
    """.trimIndent()

    val GENERATE_PATTERN = """
        Escribe UN bloque de test por cada método de la lista (incluido "apply" si existe).

        PARTES PARAMETRIZABLES DE CADA BLOQUE:
          - número:            {{N}}   (literal, el sistema numera)
          - método:            <METODO>   (nombre EXACTO del código; el principal suele ser apply)
          - entradas:          una variable <VAR_DF> por cada parámetro DataFrame del método, leída con
                               sparkDatio.read.parquet("<RUTA_RELATIVA>") usando una ruta de la lista de parquet de muestra
          - Map de entradas:   si el constructor recibe Map[String, DataFrame], arma <VAR_INPUTS> con la clase del proyecto que
                               lo devuelve (mira los archivos asociados): new <CLASE_GETDATA>(sparkDatio.getSparkSession, config).<METODO_GETDATA>
          - clave de sonda:    <CLAVE> (la de tu @@PROBE; normalmente el nombre del método)
          - filas/columnas:    {{ROWS:<CLAVE>}} y {{COLS:<CLAVE>}}

        PATRÓN A - método principal sin parámetros (apply):

          "{{N}}. {{SOURCE_NAME}}.<METODO>" should "get a dataframe with {{ROWS:<CLAVE>}} rows and {{COLS:<CLAVE>}} columns" in {
            val <VAR_INPUTS>: Map[String, DataFrame] = new <CLASE_GETDATA>(sparkDatio.getSparkSession, config).<METODO_GETDATA>
            val evaluate = new {{SOURCE_NAME}}(sparkDatio.getSparkSession, config, <VAR_INPUTS>).<METODO>
            assert(evaluate.isInstanceOf[DataFrame] &&
              evaluate.columns.length == {{COLS:<CLAVE>}} &&
              evaluate.count() == {{ROWS:<CLAVE>}}
            )
          }

        PATRÓN B - método de transformación con parámetros DataFrame:

          "{{N}}. when execute the method <METODO>" should "get a dataframe with {{ROWS:<CLAVE>}} rows and {{COLS:<CLAVE>}} columns" in {
            val <VAR_DF1> = sparkDatio.read.parquet("<RUTA_RELATIVA_1>")
            val <VAR_DF2> = sparkDatio.read.parquet("<RUTA_RELATIVA_2>")
            val <VAR_INPUTS>: Map[String, DataFrame] = new <CLASE_GETDATA>(sparkDatio.getSparkSession, config).<METODO_GETDATA>
            val evaluate = new {{SOURCE_NAME}}(sparkDatio.getSparkSession, config, <VAR_INPUTS>).<METODO>(<VAR_DF1>, <VAR_DF2>)
            assert(evaluate.isInstanceOf[DataFrame] &&
              evaluate.columns.length == {{COLS:<CLAVE>}} &&
              evaluate.count() == {{ROWS:<CLAVE>}}
            )
          }

        Usa SIEMPRE los nombres reales del método y de la clase. No inventes la firma ni las rutas.
        En la sonda de un método de transformación, copia el cuerpo del método sobre los mismos parquet que usa el test.
    """.trimIndent()
}