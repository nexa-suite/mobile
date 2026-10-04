package com.nexa.mobile.operations.core.designsystem

import androidx.annotation.DrawableRes

/** Resolves only canonical server-provided catalog file names to bundled presentation assets. */
object CatalogImageRegistry {
    private val imageResources = mapOf(
        "agriform-queso-grana-padano-dop-150g.png" to
            R.drawable.agriform_queso_grana_padano_dop_150g,
        "agriform-queso-parmigiano-reggiano-dop-150g.png" to
            R.drawable.agriform_queso_parmigiano_reggiano_dop_150g,
        "agriform_queso-grana-padano-dop_corte.png" to
            R.drawable.agriform_queso_grana_padano_dop_corte,
        "agriform_queso-grana-padano-dop_molde-37kg.png" to
            R.drawable.agriform_queso_grana_padano_dop_molde_37kg,
        "asturianas_crema-chantilly_275cc.png" to
            R.drawable.asturianas_crema_chantilly_275cc,
        "asturianas_crema-de-leche_200ml.png" to
            R.drawable.asturianas_crema_de_leche_200ml,
        "cavour-coppa-molde-3kg.png" to
            R.drawable.cavour_coppa_molde_3kg,
        "cavour-mortadella-bologna-igp-con-pistacchio-molde-7-5kg.png" to
            R.drawable.cavour_mortadella_bologna_igp_con_pistacchio_molde_7_5kg,
        "cavour-salame-milano-100g.jpeg" to
            R.drawable.cavour_salame_milano_100g,
        "cavour-salame-milano-molde-2-5kg.png" to
            R.drawable.cavour_salame_milano_molde_2_5kg,
        "cavour-salame-napoli-100g.jpeg" to
            R.drawable.cavour_salame_napoli_100g,
        "cavour-salame-napoli-molde-1-5kg.png" to
            R.drawable.cavour_salame_napoli_molde_1_5kg,
        "eru_queso-crema-ahumado_100g.png" to
            R.drawable.eru_queso_crema_ahumado_100g,
        "eru_queso-crema-gouda_100g.png" to
            R.drawable.eru_queso_crema_gouda_100g,
        "eru_queso-crema-gruyere_100g.png" to
            R.drawable.eru_queso_crema_gruyere_100g,
        "eru_queso-crema-maasdam_100g.png" to
            R.drawable.eru_queso_crema_maasdam_100g,
        "gestam-queso-ahumado-jamon-molde-3kg.png" to
            R.drawable.gestam_queso_ahumado_jamon_molde_3kg,
        "gestam-queso-ahumado-natural-molde-3kg.png" to
            R.drawable.gestam_queso_ahumado_natural_molde_3kg,
        "gestam-queso-ahumado-picante-molde-3kg.png" to
            R.drawable.gestam_queso_ahumado_picante_molde_3kg,
        "gestam-queso-edam-bola-corte.png" to
            R.drawable.gestam_queso_edam_bola_corte,
        "gestam-queso-edam-bola-molde-1-9kg.png" to
            R.drawable.gestam_queso_edam_bola_molde_1_9kg,
        "gestam-queso-gouda-cabra-corte.jpeg" to
            R.drawable.gestam_queso_gouda_cabra_corte,
        "gestam-queso-gouda-cabra-molde-5kg.png" to
            R.drawable.gestam_queso_gouda_cabra_molde_5kg,
        "gestam-queso-gouda-chili-corte.png" to
            R.drawable.gestam_queso_gouda_chili_corte,
        "gestam-queso-gouda-chili-molde-4-5kg.png" to
            R.drawable.gestam_queso_gouda_chili_molde_4_5kg,
        "gestam-queso-gouda-comino-corte.png" to
            R.drawable.gestam_queso_gouda_comino_corte,
        "gestam-queso-gouda-comino-molde-4-5kg.png" to
            R.drawable.gestam_queso_gouda_comino_molde_4_5kg,
        "gestam-queso-gouda-finas-hierbas-corte.jpeg" to
            R.drawable.gestam_queso_gouda_finas_hierbas_corte,
        "gestam-queso-gouda-finas-hierbas-molde-4-5kg.png" to
            R.drawable.gestam_queso_gouda_finas_hierbas_molde_4_5kg,
        "gestam-queso-gouda-natural-corte.png" to
            R.drawable.gestam_queso_gouda_natural_corte,
        "gestam-queso-gouda-natural-molde-4-5kg.png" to
            R.drawable.gestam_queso_gouda_natural_molde_4_5kg,
        "gestam-queso-gouda-pimienta-corte.jpeg" to
            R.drawable.gestam_queso_gouda_pimienta_corte,
        "gestam-queso-gouda-pimienta-molde-4-5kg.png" to
            R.drawable.gestam_queso_gouda_pimienta_molde_4_5kg,
        "gestam-queso-maasdam-corte.png" to
            R.drawable.gestam_queso_maasdam_corte,
        "gestam-queso-maasdam-molde-12-5kg.png" to
            R.drawable.gestam_queso_maasdam_molde_12_5kg,
        "grand-fermage_mantequilla-tourage-82_molde-1kg.jpeg" to
            R.drawable.grand_fermage_mantequilla_tourage_82_molde_1kg,
        "green-island-queso-danish-blue-100g.png" to
            R.drawable.green_island_queso_danish_blue_100g,
        "green-island-queso-danish-blue-molde-3kg.png" to
            R.drawable.green_island_queso_danish_blue_molde_3kg,
        "happy-cow-queso-slices-cheddar-48u-800g.png" to
            R.drawable.happy_cow_queso_slices_cheddar_48u_800g,
        "happy-cow_queso-triangulo-regular_120g.png" to
            R.drawable.happy_cow_queso_triangulo_regular_120g,
        "jandammer_queso-ahumado-jamon_molde-3kg.png" to
            R.drawable.jandammer_queso_ahumado_jamon_molde_3kg,
        "jandammer_queso-edam-bola_al-peso.png" to
            R.drawable.jandammer_queso_edam_bola_al_peso,
        "jandammer_queso-gouda-chili_molde-4.5kg.png" to
            R.drawable.jandammer_queso_gouda_chili_molde_4_5kg,
        "jandammer_queso-gouda-comino_al-peso.jpeg" to
            R.drawable.jandammer_queso_gouda_comino_al_peso,
        "jandammer_queso-gouda-natural_al-peso.jpeg" to
            R.drawable.jandammer_queso_gouda_natural_al_peso,
        "jandammer_queso-gouda-natural_molde-3kg.png" to
            R.drawable.jandammer_queso_gouda_natural_molde_3kg,
        "jandammer_queso-maasdam_al-peso.jpeg" to
            R.drawable.jandammer_queso_maasdam_al_peso,
        "le-charcutier-prosciutto-crudo-premium-mattonella-molde-4kg.png" to
            R.drawable.le_charcutier_prosciutto_crudo_premium_mattonella_molde_4kg,
        "le-charcutier_mortadella-con-pistacchio_100g.jpeg" to
            R.drawable.le_charcutier_mortadella_con_pistacchio_100g,
        "le-charcutier_prosciutto-crudo-premium_100g.png" to
            R.drawable.le_charcutier_prosciutto_crudo_premium_100g,
        "le-fromelier-queso-feta-dop-corte.png" to
            R.drawable.le_fromelier_queso_feta_dop_corte,
        "le-fromelier_queso-feta-43_mg.png" to
            R.drawable.le_fromelier_queso_feta_43_mg,
        "monti-trentini_queso-para-rayar_molde.png" to
            R.drawable.monti_trentini_queso_para_rayar_molde,
        "paysan-breton-mantequilla-con-sal-20x10g.png" to
            R.drawable.paysan_breton_mantequilla_con_sal_20x10g,
        "paysan-breton-mantequilla-sin-sal-20x10g.png" to
            R.drawable.paysan_breton_mantequilla_sin_sal_20x10g,
        "paysan-breton-queso-bleu-dauvergne-125g.png" to
            R.drawable.paysan_breton_queso_bleu_dauvergne_125g,
        "paysan-breton-queso-de-cabra-afh-100g.png" to
            R.drawable.paysan_breton_queso_de_cabra_afh_100g,
        "paysan-breton-queso-de-cabra-miel-100g.png" to
            R.drawable.paysan_breton_queso_de_cabra_miel_100g,
        "paysan-breton-queso-de-cabra-natural-100g.png" to
            R.drawable.paysan_breton_queso_de_cabra_natural_100g,
        "paysan-breton-queso-de-cabra-pimienta-100g.png" to
            R.drawable.paysan_breton_queso_de_cabra_pimienta_100g,
        "paysan-breton-queso-emmental-corte.png" to
            R.drawable.paysan_breton_queso_emmental_corte,
        "paysan-breton-queso-emmental-molde-3-5kg.png" to
            R.drawable.paysan_breton_queso_emmental_molde_3_5kg,
        "paysan-breton-queso-petit-moule-afh-150g.png" to
            R.drawable.paysan_breton_queso_petit_moule_afh_150g,
        "paysan-breton-raclette-slices-400g.png" to
            R.drawable.paysan_breton_raclette_slices_400g,
        "paysan-breton_mantequilla-con-sal_200g.png" to
            R.drawable.paysan_breton_mantequilla_con_sal_200g,
        "paysan-breton_mantequilla-sin-sal_200g.png" to
            R.drawable.paysan_breton_mantequilla_sin_sal_200g,
        "paysan-breton_queso-brie_molde-3kg.png" to
            R.drawable.paysan_breton_queso_brie_molde_3kg,
        "paysan-breton_queso-emmental_220g.jpeg" to
            R.drawable.paysan_breton_queso_emmental_220g,
        "paysan-breton_roquefort-dop_100g.png" to
            R.drawable.paysan_breton_roquefort_dop_100g,
        "sancho-panza-queso-manchego-dop-12-meses-corte.png" to
            R.drawable.sancho_panza_queso_manchego_dop_12_meses_corte,
        "sancho-panza-queso-manchego-dop-3-meses-corte.png" to
            R.drawable.sancho_panza_queso_manchego_dop_3_meses_corte,
        "sancho-panza-queso-manchego-dop-3-meses-molde-3kg.png" to
            R.drawable.sancho_panza_queso_manchego_dop_3_meses_molde_3kg,
        "sancho-panza-queso-manchego-dop-6-meses-corte.png" to
            R.drawable.sancho_panza_queso_manchego_dop_6_meses_corte,
        "sancho-panza-queso-manchego-dop-6-meses-molde-3kg.png" to
            R.drawable.sancho_panza_queso_manchego_dop_6_meses_molde_3kg,
        "sancho-panza_queso-manchego-dop-12-meses_molde-3kg.png" to
            R.drawable.sancho_panza_queso_manchego_dop_12_meses_molde_3kg,
        "sant-dalmai_jamon-de-pechuga-de-pavo-ahumado_150g.jpeg" to
            R.drawable.sant_dalmai_jamon_de_pechuga_de_pavo_ahumado_150g,
        "sant-dalmai_jamon-de-pierna-ahumado_150g.jpeg" to
            R.drawable.sant_dalmai_jamon_de_pierna_ahumado_150g,
        "sant-dalmai_jamon-de-pierna_150g.jpeg" to
            R.drawable.sant_dalmai_jamon_de_pierna_150g,
        "sant-dalmai_jamon-ingles-ahumado_150g.jpeg" to
            R.drawable.sant_dalmai_jamon_ingles_ahumado_150g,
        "sant-dalmai_jamon-ingles_150g.jpeg" to
            R.drawable.sant_dalmai_jamon_ingles_150g,
        "sant-dalmai_mortadela-oro_150g.jpeg" to
            R.drawable.sant_dalmai_mortadela_oro_150g,
        "sant-dalmai_pechuga-de-pavo-tradicional_150g.jpeg" to
            R.drawable.sant_dalmai_pechuga_de_pavo_tradicional_150g,
        "sant-dalmai_tocino-ahumado-daditos_500g.jpeg" to
            R.drawable.sant_dalmai_tocino_ahumado_daditos_500g,
        "sant-dalmai_tocino-ahumado_150g.jpeg" to
            R.drawable.sant_dalmai_tocino_ahumado_150g,
        "sant-dalmai_tocino-ahumado_pieza-200g.jpeg" to
            R.drawable.sant_dalmai_tocino_ahumado_pieza_200g,
        "soignon_brie-de-cabra_molde-1kg.png" to
            R.drawable.soignon_brie_de_cabra_molde_1kg,
        "soignon_piramide-de-cabra-afh_140g.png" to
            R.drawable.soignon_piramide_de_cabra_afh_140g,
        "soignon_piramide-de-cabra-higo_150g.png" to
            R.drawable.soignon_piramide_de_cabra_higo_150g,
        "soignon_piramide-de-cabra-miel_150g.png" to
            R.drawable.soignon_piramide_de_cabra_miel_150g,
        "soignon_piramide-de-cabra-natural_150g.png" to
            R.drawable.soignon_piramide_de_cabra_natural_150g,
        "soignon_queso-rulo-de-cabra-natural_molde-1kg.png" to
            R.drawable.soignon_queso_rulo_de_cabra_natural_molde_1kg,
        "soignon_rulo-de-cabra-afh_110g.png" to
            R.drawable.soignon_rulo_de_cabra_afh_110g,
        "soignon_rulo-de-cabra-miel_110g.png" to
            R.drawable.soignon_rulo_de_cabra_miel_110g,
        "soignon_rulo-de-cabra-natural_110g.png" to
            R.drawable.soignon_rulo_de_cabra_natural_110g,
        "st-clements_queso-danish-brie_125g.jpeg" to
            R.drawable.st_clements_queso_danish_brie_125g,
        "st-clements_queso-danish-camembert_125g.jpeg" to
            R.drawable.st_clements_queso_danish_camembert_125g,
        "sterilgarda-pannacotta-caramel-2x90g.png" to
            R.drawable.sterilgarda_pannacotta_caramel_2x90g,
        "sterilgarda-queso-mascarpone-uht-500g.png" to
            R.drawable.sterilgarda_queso_mascarpone_uht_500g,
        "sterilgarda_pannacotta-chocolate_2x90g.png" to
            R.drawable.sterilgarda_pannacotta_chocolate_2x90g,
        "trevalli_coppa_50g.png" to
            R.drawable.trevalli_coppa_50g,
        "trevalli_salame-milano_50g.jpeg" to
            R.drawable.trevalli_salame_milano_50g,
        "trevalli_salame-napoli_50g.jpeg" to
            R.drawable.trevalli_salame_napoli_50g
    )

    /** Number of canonical catalog images bundled with this client. */
    val canonicalFileNameCount: Int
        get() = imageResources.size

    /** Returns null for missing, non-canonical or path-bearing values. */
    @DrawableRes
    fun resolve(fileName: String?): Int? = fileName?.let(imageResources::get)
}
