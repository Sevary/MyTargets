/*
 * Copyright (C) 2018 Florian Dreier
 *
 * This file is part of MyTargets.
 *
 * MyTargets is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 *
 * MyTargets is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package de.dreier.mytargets.base.gallery.adapters

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.squareup.picasso.Picasso
import com.squareup.picasso.Transformation
import de.dreier.mytargets.R
import de.dreier.mytargets.base.gallery.HorizontalImageViewHolder
import de.dreier.mytargets.utils.ImageList
import java.io.File
import kotlin.math.max

typealias OnItemClickListener = (Int) -> Unit

class HorizontalListAdapters(
    private val activity: Activity,
    private val images: ImageList,
    private val clickListener: OnItemClickListener
) : RecyclerView.Adapter<HorizontalImageViewHolder>() {

    private var selectedItem = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HorizontalImageViewHolder {
        return HorizontalImageViewHolder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_horizontal, parent, false)
        )
    }

    override fun onBindViewHolder(holder: HorizontalImageViewHolder, position: Int) {
        val pos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
        if (pos == images.size()) {
            holder.image.visibility = View.GONE
            holder.camera.visibility = View.VISIBLE
            android.util.Log.d("HorizListAdapter", "bind: pos=$pos isCamera=true")
        } else {
            holder.camera.visibility = View.GONE
            holder.image.visibility = View.VISIBLE
            val file = File(activity.filesDir, images[pos].fileName)
            android.util.Log.d("HorizListAdapter", "bind: pos=$pos file=${file.absolutePath} exists=${file.exists()}")

            try {
                android.util.Log.d("HorizListAdapter", "fileSize=${file.length()} bytes for pos=$pos")
            } catch (e: Exception) {
                android.util.Log.d("HorizListAdapter", "fileSize unknown for pos=$pos")
            }

            // cancel previous request and clear image to avoid recycled-view issues
            try {
                Picasso.with(activity).cancelRequest(holder.image)
            } catch (_: Exception) {
            }
            holder.image.setImageDrawable(null)

            val density = activity.resources.displayMetrics.density
            val defaultDp = 72
            val defaultPx = (defaultDp * density).toInt()
            val targetW = if (holder.image.width > 0) holder.image.width else defaultPx
            val targetH = if (holder.image.height > 0) holder.image.height else defaultPx
            val safeW = max(1, targetW)
            val safeH = max(1, targetH)
            android.util.Log.d("HorizListAdapter", "picasso: target w=$safeW h=$safeH pos=$pos")

            // Quick bounds decode to decide whether to do manual downsample
            val quickBounds = BitmapFactory.Options()
            quickBounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, quickBounds)
            android.util.Log.d("HorizListAdapter", "quick bounds: w=${quickBounds.outWidth} h=${quickBounds.outHeight} pos=$pos")

            val largeThresholdMultiplier = 4
            val shouldManual = quickBounds.outWidth > safeW * largeThresholdMultiplier || quickBounds.outHeight > safeH * largeThresholdMultiplier
            if (shouldManual) {
                android.util.Log.d("HorizListAdapter", "performing manual downsample for pos=$pos")
                try {
                    val bmp = de.dreier.mytargets.base.gallery.ImageUtil.decodeSampledBitmapFromFile(file.absolutePath, safeW, safeH)
                    if (bmp != null) {
                        holder.image.setImageBitmap(bmp)
                        android.util.Log.d("HorizListAdapter", "manual decode success: pos=$pos bmp=${bmp.width}x${bmp.height}")
                    } else {
                        android.util.Log.e("HorizListAdapter", "manual decode failed: pos=$pos file=${file.absolutePath}")
                        Picasso.with(activity)
                            .load(file)
                            .transform(de.dreier.mytargets.base.gallery.ImageUtil.ExifRotateTransformation(file.absolutePath))
                            .resize(safeW, safeH)
                            .onlyScaleDown()
                            .centerCrop()
                            .config(Bitmap.Config.RGB_565)
                            .into(holder.image, object : com.squareup.picasso.Callback {
                                override fun onSuccess() {
                                    android.util.Log.d("HorizListAdapter", "onSuccess (fallback): pos=$pos file=${file.absolutePath}")
                                }

                                override fun onError() {
                                    android.util.Log.d("HorizListAdapter", "onError (fallback): pos=$pos file=${file.absolutePath}")
                                }
                            })
                     }
                 } catch (e: Throwable) {
                     android.util.Log.e("HorizListAdapter", "manual decode exception for pos=$pos file=${file.absolutePath}", e)
                     // Picasso fallback with rotation transformation
                    Picasso.with(activity)
                        .load(file)
                        .transform(de.dreier.mytargets.base.gallery.ImageUtil.ExifRotateTransformation(file.absolutePath))
                        .resize(safeW, safeH)
                        .onlyScaleDown()
                        .centerCrop()
                        .config(Bitmap.Config.RGB_565)
                        .into(holder.image, object : com.squareup.picasso.Callback {
                            override fun onSuccess() {
                                android.util.Log.d("HorizListAdapter", "onSuccess (fallback after exception): pos=$pos file=${file.absolutePath}")
                            }

                            override fun onError() {
                                android.util.Log.d("HorizListAdapter", "onError (fallback after exception): pos=$pos file=${file.absolutePath}")
                            }
                        })
                 }
             } else {
                Picasso.with(activity)
                    .load(file)
                    .transform(de.dreier.mytargets.base.gallery.ImageUtil.ExifRotateTransformation(file.absolutePath))
                    .resize(safeW, safeH)
                    .onlyScaleDown()
                    .centerCrop()
                    .config(Bitmap.Config.RGB_565)
                    .into(holder.image, object : com.squareup.picasso.Callback {
                        override fun onSuccess() {
                            android.util.Log.d("HorizListAdapter", "onSuccess: pos=$pos file=${file.absolutePath}")
                        }

                        override fun onError() {
                            android.util.Log.d("HorizListAdapter", "onError: pos=$pos file=${file.absolutePath} - falling back to manual decode")
                            try {
                                val bmp = de.dreier.mytargets.base.gallery.ImageUtil.decodeSampledBitmapFromFile(file.absolutePath, safeW, safeH)
                                if (bmp != null) {
                                    holder.image.setImageBitmap(bmp)
                                    android.util.Log.d("HorizListAdapter", "manual decode success: pos=$pos bmp=${bmp.width}x${bmp.height}")
                                } else {
                                    android.util.Log.e("HorizListAdapter", "manual decode failed: pos=$pos file=${file.absolutePath}")
                                }
                            } catch (e: Throwable) {
                                android.util.Log.e("HorizListAdapter", "manual decode exception for pos=$pos file=${file.absolutePath}", e)
                            }
                        }
                    })
             }
         }

        holder.itemView.setOnClickListener { clickListener.invoke(pos) }
    }

    override fun onViewRecycled(holder: HorizontalImageViewHolder) {
        try {
            Picasso.with(activity).cancelRequest(holder.image)
        } catch (_: Exception) {
        }
        holder.image.setImageDrawable(null)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        val count = images.size() + 1
        return count
    }

    fun setSelectedItem(position: Int) {
        android.util.Log.d("HorizListAdapter", "setSelectedItem: pos=$position")
        selectedItem = position
        notifyDataSetChanged()
    }
}
