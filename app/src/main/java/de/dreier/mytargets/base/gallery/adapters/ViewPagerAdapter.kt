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
import androidx.viewpager.widget.PagerAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.widget.Toolbar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.RelativeLayout
import com.github.chrisbanes.photoview.PhotoView
import com.squareup.picasso.Callback
import com.squareup.picasso.Picasso
import de.dreier.mytargets.R
import de.dreier.mytargets.utils.ImageList
import de.dreier.mytargets.utils.Utils
import java.io.File
import kotlin.collections.get
import kotlin.compareTo
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import timber.log.Timber

class ViewPagerAdapter(
    private val activity: Activity,
    private val images: ImageList,
    private val toolbar: Toolbar,
    private val imagesHorizontalList: RecyclerView
) : PagerAdapter() {
    private val layoutInflater = LayoutInflater.from(activity)
    private var isShowing = true

    override fun getCount(): Int {
        return images.size()
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return view == `object`
    }

    override fun getItemPosition(`object`: Any): Int {
        return PagerAdapter.POSITION_NONE
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val itemView = layoutInflater.inflate(R.layout.pager_item, container, false)
        val imageView = itemView.findViewById<PhotoView>(R.id.iv)
        val image = images[position]
        val file = File(activity.filesDir, image.fileName)
        Timber.d("instantiateItem: pos=$position file=${file.absolutePath} exists=${file.exists()}")

        imageView.post {
            val targetW = if (imageView.width > 0) imageView.width else container.width
            val targetH = if (imageView.height > 0) imageView.height else container.height
            Timber.d("instantiateItem: targetSize w=$targetW h=$targetH pos=$position")

            try {
                val bmp = de.dreier.mytargets.base.gallery.ImageUtil.decodeSampledBitmapFromFile(file.absolutePath, targetW, targetH)
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    Timber.d("onSuccess: loaded pos=$position file=${file.absolutePath} bmp=${bmp.width}x${bmp.height}")
                    imageView.setOnPhotoTapListener { _, _, _ -> toggleToolbar() }
                } else {
                    Timber.e("onError: decode returned null pos=$position file=${file.absolutePath}")
                }
            } catch (e: Throwable) {
                Timber.e(e, "onError: failed to decode pos=$position file=${file.absolutePath}")
            }
        }

        container.addView(itemView)
        return itemView
    }

    private fun applyExifRotationIfNeeded(bmp: Bitmap, path: String): Bitmap {
        return de.dreier.mytargets.base.gallery.ImageUtil.applyExifRotationIfNeeded(bmp, path)
    }

    private fun toggleToolbar() {
        if (isShowing) {
            isShowing = false
            toolbar.animate()
                .translationY((-toolbar.bottom).toFloat())
                .setInterpolator(AccelerateInterpolator())
                .start()
            imagesHorizontalList.animate()
                .translationY(imagesHorizontalList.bottom.toFloat())
                .setInterpolator(AccelerateInterpolator())
                .start()
            Utils.hideSystemUI(activity)
        } else {
            isShowing = true
            toolbar.animate()
                .translationY(0f)
                .setInterpolator(DecelerateInterpolator())
                .start()
            imagesHorizontalList.animate()
                .translationY(0f)
                .setInterpolator(DecelerateInterpolator())
                .start()
            Utils.showSystemUI(activity)
        }
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(`object` as RelativeLayout)
    }

}
