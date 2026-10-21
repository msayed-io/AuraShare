package com.example.ui.components

import android.content.Context
import android.net.Uri
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest

/** A video URI needs an actual frame decoder; the still-image decoder cannot render it. */
fun videoThumbnailRequest(context: Context, uri: Uri): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .decoderFactory(VideoFrameDecoder.Factory())
        .crossfade(true)
        .build()
