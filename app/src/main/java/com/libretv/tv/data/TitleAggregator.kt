package com.libretv.tv.data

// 聚合模型已迁至 `domain/model/Title.kt`。旧包名保留为 typealias，
// 顶层函数（aggregate / detectQuality / latencyTier / formatLatency）由
// `domain.model` 包提供，引用方改为 import 新包。
typealias QualityTier = com.libretv.tv.domain.model.QualityTier
typealias LatencyTier = com.libretv.tv.domain.model.LatencyTier
typealias SourceOffer = com.libretv.tv.domain.model.SourceOffer
typealias TitleGroup = com.libretv.tv.domain.model.TitleGroup
typealias OfferProbe = com.libretv.tv.domain.model.OfferProbe
