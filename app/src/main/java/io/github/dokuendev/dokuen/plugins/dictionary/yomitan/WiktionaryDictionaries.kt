package io.github.dokuendev.dokuen.plugins.dictionary.yomitan

import androidx.annotation.StringRes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A language offered by wiktionary-to-yomitan (wty), keyed by the code its file paths use. */
data class WiktionaryLanguage(val code: String, @StringRes val nameRes: Int)

/** The kinds of dictionary wty builds, in the order its download page lists them. */
enum class WiktionaryDictionaryType(
    val metadataKey: String,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int
) {
    MAIN("main", R.string.wiktionary_type_main, R.string.wiktionary_type_main_desc),
    GLOSSARY("glossary", R.string.wiktionary_type_glossary, R.string.wiktionary_type_glossary_desc),
    IPA("ipa", R.string.wiktionary_type_ipa, R.string.wiktionary_type_ipa_desc),

    /** Pronunciations from every Wiktionary edition, which depend only on the source language. */
    IPA_MERGED("ipa-merged", R.string.wiktionary_type_ipa_merged, R.string.wiktionary_type_ipa_merged_desc);

    val isPair: Boolean get() = this != IPA_MERGED
}

/** One downloadable wty dictionary. [title] is the title in its index.json. */
data class WiktionaryDictionary(
    val type: WiktionaryDictionaryType,
    val title: String,
    val size: String,
    val url: String
)

/**
 * The dictionaries of the latest wty release, read from the release metadata its download page
 * uses. The metadata maps each type to its source languages, and each source to the target
 * languages it has a dictionary for, with that dictionary's display size.
 */
class WiktionaryCatalog(private val sizes: Map<WiktionaryDictionaryType, Map<String, Map<String, String>>>) {

    /** Targets that some pair dictionary covers for [source]. */
    fun availableTargets(source: String): Set<String> =
        WiktionaryDictionaryType.entries.filter { it.isPair }
            .flatMapTo(mutableSetOf()) { sizes[it]?.get(source)?.keys.orEmpty() }

    /** Every dictionary there is for the pair, in display order. */
    fun dictionariesFor(source: String, target: String): List<WiktionaryDictionary> =
        WiktionaryDictionaryType.entries.mapNotNull { type ->
            val size = if (type.isPair) {
                sizes[type]?.get(source)?.get(target)
            } else {
                sizes[type]?.get(MERGED_SOURCE)?.get(source)
            }
            size?.let {
                val title = WiktionaryDictionaries.title(type, source, target)
                WiktionaryDictionary(type, title, it, WiktionaryDictionaries.url(type, source, target))
            }
        }

    companion object {
        /** The merged IPA dictionaries are listed under this source, keyed by their language. */
        private const val MERGED_SOURCE = "all"

        fun parse(json: String): WiktionaryCatalog {
            val dicts = Json.parseToJsonElement(json).jsonObject.getValue("dicts").jsonObject
            val sizes = WiktionaryDictionaryType.entries.associateWith { type ->
                val sources = dicts[type.metadataKey]?.jsonObject?.get("sources") as? JsonObject
                sources.orEmpty().mapValues { (_, source) ->
                    val targets = source.jsonObject["targets"] as? JsonObject
                    targets.orEmpty().mapValues { (_, size) -> size.jsonPrimitive.content }
                }
            }
            return WiktionaryCatalog(sizes)
        }
    }
}

object WiktionaryDictionaries {
    const val DOWNLOAD_PAGE_URL = "https://yomidevs.github.io/wiktionary-to-yomitan/download/"
    const val METADATA_URL = "https://yomidevs.github.io/wiktionary-to-yomitan/release_metadata_yomitan.json"
    private const val BASE_URL = "https://huggingface.co/datasets/daxida/wty-release/resolve/main/latest/dict"
    const val DEFAULT_SOURCE = "ja"

    /** A dictionary's file is named after its title, which is all wty puts in the file name. */
    fun title(type: WiktionaryDictionaryType, source: String, target: String): String = when (type) {
        WiktionaryDictionaryType.MAIN -> "wty-$source-$target"
        WiktionaryDictionaryType.GLOSSARY -> "wty-$source-$target-gloss"
        WiktionaryDictionaryType.IPA -> "wty-$source-$target-ipa"
        WiktionaryDictionaryType.IPA_MERGED -> "wty-$source-ipa"
    }

    fun url(type: WiktionaryDictionaryType, source: String, target: String): String {
        val folder = if (type.isPair) "$source/$target" else "all/$source"
        return "$BASE_URL/$folder/${title(type, source, target)}.zip"
    }

    /** The languages of wty's download page, in its order. */
    val LANGUAGES = listOf(
        WiktionaryLanguage("af", R.string.wiktionary_language_af),
        WiktionaryLanguage("sq", R.string.wiktionary_language_sq),
        WiktionaryLanguage("arz", R.string.wiktionary_language_arz),
        WiktionaryLanguage("afb", R.string.wiktionary_language_afb),
        WiktionaryLanguage("ar", R.string.wiktionary_language_ar),
        WiktionaryLanguage("apc", R.string.wiktionary_language_apc),
        WiktionaryLanguage("ajp", R.string.wiktionary_language_ajp),
        WiktionaryLanguage("hy", R.string.wiktionary_language_hy),
        WiktionaryLanguage("xcl", R.string.wiktionary_language_xcl),
        WiktionaryLanguage("rup", R.string.wiktionary_language_rup),
        WiktionaryLanguage("as", R.string.wiktionary_language_as),
        WiktionaryLanguage("aii", R.string.wiktionary_language_aii),
        WiktionaryLanguage("ast", R.string.wiktionary_language_ast),
        WiktionaryLanguage("az", R.string.wiktionary_language_az),
        WiktionaryLanguage("ba", R.string.wiktionary_language_ba),
        WiktionaryLanguage("eu", R.string.wiktionary_language_eu),
        WiktionaryLanguage("be", R.string.wiktionary_language_be),
        WiktionaryLanguage("bn", R.string.wiktionary_language_bn),
        WiktionaryLanguage("bcl", R.string.wiktionary_language_bcl),
        WiktionaryLanguage("bg", R.string.wiktionary_language_bg),
        WiktionaryLanguage("my", R.string.wiktionary_language_my),
        WiktionaryLanguage("ca", R.string.wiktionary_language_ca),
        WiktionaryLanguage("ceb", R.string.wiktionary_language_ceb),
        WiktionaryLanguage("zh", R.string.wiktionary_language_zh),
        WiktionaryLanguage("yue", R.string.wiktionary_language_yue),
        WiktionaryLanguage("cmn", R.string.wiktionary_language_cmn),
        WiktionaryLanguage("cim", R.string.wiktionary_language_cim),
        WiktionaryLanguage("cop", R.string.wiktionary_language_cop),
        WiktionaryLanguage("kw", R.string.wiktionary_language_kw),
        WiktionaryLanguage("crh", R.string.wiktionary_language_crh),
        WiktionaryLanguage("cs", R.string.wiktionary_language_cs),
        WiktionaryLanguage("zlw-ocs", R.string.wiktionary_language_zlw_ocs),
        WiktionaryLanguage("da", R.string.wiktionary_language_da),
        WiktionaryLanguage("nl", R.string.wiktionary_language_nl),
        WiktionaryLanguage("dum", R.string.wiktionary_language_dum),
        WiktionaryLanguage("egy", R.string.wiktionary_language_egy),
        WiktionaryLanguage("en", R.string.wiktionary_language_en),
        WiktionaryLanguage("enm", R.string.wiktionary_language_enm),
        WiktionaryLanguage("ang", R.string.wiktionary_language_ang),
        WiktionaryLanguage("simple", R.string.wiktionary_language_simple),
        WiktionaryLanguage("eo", R.string.wiktionary_language_eo),
        WiktionaryLanguage("et", R.string.wiktionary_language_et),
        WiktionaryLanguage("fo", R.string.wiktionary_language_fo),
        WiktionaryLanguage("fi", R.string.wiktionary_language_fi),
        WiktionaryLanguage("fr", R.string.wiktionary_language_fr),
        WiktionaryLanguage("frm", R.string.wiktionary_language_frm),
        WiktionaryLanguage("fro", R.string.wiktionary_language_fro),
        WiktionaryLanguage("gl", R.string.wiktionary_language_gl),
        WiktionaryLanguage("ka", R.string.wiktionary_language_ka),
        WiktionaryLanguage("de", R.string.wiktionary_language_de),
        WiktionaryLanguage("goh", R.string.wiktionary_language_goh),
        WiktionaryLanguage("got", R.string.wiktionary_language_got),
        WiktionaryLanguage("el", R.string.wiktionary_language_el),
        WiktionaryLanguage("grc", R.string.wiktionary_language_grc),
        WiktionaryLanguage("gu", R.string.wiktionary_language_gu),
        WiktionaryLanguage("haw", R.string.wiktionary_language_haw),
        WiktionaryLanguage("he", R.string.wiktionary_language_he),
        WiktionaryLanguage("hi", R.string.wiktionary_language_hi),
        WiktionaryLanguage("hu", R.string.wiktionary_language_hu),
        WiktionaryLanguage("is", R.string.wiktionary_language_is),
        WiktionaryLanguage("io", R.string.wiktionary_language_io),
        WiktionaryLanguage("id", R.string.wiktionary_language_id),
        WiktionaryLanguage("izh", R.string.wiktionary_language_izh),
        WiktionaryLanguage("ia", R.string.wiktionary_language_ia),
        WiktionaryLanguage("ga", R.string.wiktionary_language_ga),
        WiktionaryLanguage("sga", R.string.wiktionary_language_sga),
        WiktionaryLanguage("it", R.string.wiktionary_language_it),
        WiktionaryLanguage("ja", R.string.wiktionary_language_ja),
        WiktionaryLanguage("jv", R.string.wiktionary_language_jv),
        WiktionaryLanguage("kn", R.string.wiktionary_language_kn),
        WiktionaryLanguage("csb", R.string.wiktionary_language_csb),
        WiktionaryLanguage("kk", R.string.wiktionary_language_kk),
        WiktionaryLanguage("kix", R.string.wiktionary_language_kix),
        WiktionaryLanguage("km", R.string.wiktionary_language_km),
        WiktionaryLanguage("ko", R.string.wiktionary_language_ko),
        WiktionaryLanguage("ku", R.string.wiktionary_language_ku),
        WiktionaryLanguage("kmr", R.string.wiktionary_language_kmr),
        WiktionaryLanguage("ky", R.string.wiktionary_language_ky),
        WiktionaryLanguage("lld", R.string.wiktionary_language_lld),
        WiktionaryLanguage("lo", R.string.wiktionary_language_lo),
        WiktionaryLanguage("la", R.string.wiktionary_language_la),
        WiktionaryLanguage("lv", R.string.wiktionary_language_lv),
        WiktionaryLanguage("lzz", R.string.wiktionary_language_lzz),
        WiktionaryLanguage("lt", R.string.wiktionary_language_lt),
        WiktionaryLanguage("liv", R.string.wiktionary_language_liv),
        WiktionaryLanguage("lb", R.string.wiktionary_language_lb),
        WiktionaryLanguage("mk", R.string.wiktionary_language_mk),
        WiktionaryLanguage("mg", R.string.wiktionary_language_mg),
        WiktionaryLanguage("ms", R.string.wiktionary_language_ms),
        WiktionaryLanguage("ml", R.string.wiktionary_language_ml),
        WiktionaryLanguage("mt", R.string.wiktionary_language_mt),
        WiktionaryLanguage("gv", R.string.wiktionary_language_gv),
        WiktionaryLanguage("mr", R.string.wiktionary_language_mr),
        WiktionaryLanguage("mn", R.string.wiktionary_language_mn),
        WiktionaryLanguage("mi", R.string.wiktionary_language_mi),
        WiktionaryLanguage("nah", R.string.wiktionary_language_nah),
        WiktionaryLanguage("nv", R.string.wiktionary_language_nv),
        WiktionaryLanguage("nrf", R.string.wiktionary_language_nrf),
        WiktionaryLanguage("se", R.string.wiktionary_language_se),
        WiktionaryLanguage("no", R.string.wiktionary_language_no),
        WiktionaryLanguage("nb", R.string.wiktionary_language_nb),
        WiktionaryLanguage("nn", R.string.wiktionary_language_nn),
        WiktionaryLanguage("oc", R.string.wiktionary_language_oc),
        WiktionaryLanguage("or", R.string.wiktionary_language_or),
        WiktionaryLanguage("cu", R.string.wiktionary_language_cu),
        WiktionaryLanguage("non", R.string.wiktionary_language_non),
        WiktionaryLanguage("pi", R.string.wiktionary_language_pi),
        WiktionaryLanguage("rsk", R.string.wiktionary_language_rsk),
        WiktionaryLanguage("fa", R.string.wiktionary_language_fa),
        WiktionaryLanguage("pdt", R.string.wiktionary_language_pdt),
        WiktionaryLanguage("pl", R.string.wiktionary_language_pl),
        WiktionaryLanguage("zlw-opl", R.string.wiktionary_language_zlw_opl),
        WiktionaryLanguage("pt", R.string.wiktionary_language_pt),
        WiktionaryLanguage("urj-fin-pro", R.string.wiktionary_language_urj_fin_pro),
        WiktionaryLanguage("gem-pro", R.string.wiktionary_language_gem_pro),
        WiktionaryLanguage("sla-pro", R.string.wiktionary_language_sla_pro),
        WiktionaryLanguage("gmw-pro", R.string.wiktionary_language_gmw_pro),
        WiktionaryLanguage("pa", R.string.wiktionary_language_pa),
        WiktionaryLanguage("ro", R.string.wiktionary_language_ro),
        WiktionaryLanguage("ru", R.string.wiktionary_language_ru),
        WiktionaryLanguage("sa", R.string.wiktionary_language_sa),
        WiktionaryLanguage("sco", R.string.wiktionary_language_sco),
        WiktionaryLanguage("gd", R.string.wiktionary_language_gd),
        WiktionaryLanguage("sh", R.string.wiktionary_language_sh),
        WiktionaryLanguage("scn", R.string.wiktionary_language_scn),
        WiktionaryLanguage("sk", R.string.wiktionary_language_sk),
        WiktionaryLanguage("sl", R.string.wiktionary_language_sl),
        WiktionaryLanguage("dsb", R.string.wiktionary_language_dsb),
        WiktionaryLanguage("es", R.string.wiktionary_language_es),
        WiktionaryLanguage("su", R.string.wiktionary_language_su),
        WiktionaryLanguage("sw", R.string.wiktionary_language_sw),
        WiktionaryLanguage("sv", R.string.wiktionary_language_sv),
        WiktionaryLanguage("syc", R.string.wiktionary_language_syc),
        WiktionaryLanguage("tl", R.string.wiktionary_language_tl),
        WiktionaryLanguage("tg", R.string.wiktionary_language_tg),
        WiktionaryLanguage("ta", R.string.wiktionary_language_ta),
        WiktionaryLanguage("tt", R.string.wiktionary_language_tt),
        WiktionaryLanguage("te", R.string.wiktionary_language_te),
        WiktionaryLanguage("th", R.string.wiktionary_language_th),
        WiktionaryLanguage("bo", R.string.wiktionary_language_bo),
        WiktionaryLanguage("tok", R.string.wiktionary_language_tok),
        WiktionaryLanguage("tr", R.string.wiktionary_language_tr),
        WiktionaryLanguage("ota", R.string.wiktionary_language_ota),
        WiktionaryLanguage("uk", R.string.wiktionary_language_uk),
        WiktionaryLanguage("ur", R.string.wiktionary_language_ur),
        WiktionaryLanguage("ug", R.string.wiktionary_language_ug),
        WiktionaryLanguage("uz", R.string.wiktionary_language_uz),
        WiktionaryLanguage("vec", R.string.wiktionary_language_vec),
        WiktionaryLanguage("vi", R.string.wiktionary_language_vi),
        WiktionaryLanguage("vo", R.string.wiktionary_language_vo),
        WiktionaryLanguage("vot", R.string.wiktionary_language_vot),
        WiktionaryLanguage("cy", R.string.wiktionary_language_cy),
        WiktionaryLanguage("ady", R.string.wiktionary_language_ady),
        WiktionaryLanguage("fy", R.string.wiktionary_language_fy),
        WiktionaryLanguage("sah", R.string.wiktionary_language_sah),
        WiktionaryLanguage("yi", R.string.wiktionary_language_yi),
        WiktionaryLanguage("yo", R.string.wiktionary_language_yo),
        WiktionaryLanguage("zu", R.string.wiktionary_language_zu),
    )
}
