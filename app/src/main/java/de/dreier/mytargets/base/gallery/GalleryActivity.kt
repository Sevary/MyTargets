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

package de.dreier.mytargets.base.gallery

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.AsyncTask
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager.widget.ViewPager
import com.afollestad.materialdialogs.MaterialDialog
import com.evernote.android.state.State
import de.dreier.mytargets.R
import de.dreier.mytargets.base.activities.ChildActivityBase
import de.dreier.mytargets.base.gallery.adapters.HorizontalListAdapters
import de.dreier.mytargets.base.gallery.adapters.ViewPagerAdapter
import de.dreier.mytargets.base.navigation.NavigationController
import de.dreier.mytargets.databinding.ActivityGalleryBinding
import de.dreier.mytargets.utils.*
import kotlinx.coroutines.Job
import permissions.dispatcher.NeedsPermission
import permissions.dispatcher.RuntimePermissions
import pl.aprilapps.easyphotopicker.DefaultCallback
import pl.aprilapps.easyphotopicker.EasyImage
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import de.dreier.mytargets.ml.MlTargetProcessor

@RuntimePermissions
class GalleryActivity : ChildActivityBase() {

    private var mlJob: Job? = null

    internal var adapter: ViewPagerAdapter? = null
    internal var layoutManager: LinearLayoutManager? = null
    internal lateinit var previewAdapter: HorizontalListAdapters

    @State
    lateinit var imageList: ImageList

    private lateinit var binding: ActivityGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("onCreate: savedInstanceState=${savedInstanceState != null}")

        binding = DataBindingUtil.setContentView(this, R.layout.activity_gallery)

        val title = intent.getStringExtra(EXTRA_TITLE)
        if (savedInstanceState == null) {
            imageList = intent.parcelableExtra(intent,EXTRA_IMAGES) ?: ImageList()
        }

        setSupportActionBar(binding.toolbar)

        ToolbarUtils.showHomeAsUp(this)
        if (title != null) {
            ToolbarUtils.setTitle(this, title)
        }
        Utils.showSystemUI(this)

        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.imagesHorizontalList.layoutManager = layoutManager

        adapter = ViewPagerAdapter(this, imageList, binding.toolbar, binding.imagesHorizontalList)
        binding.pager.adapter = adapter
        Timber.d("onCreate: adapter set, initial imageList.size=${imageList.size()}")

        previewAdapter = HorizontalListAdapters(this, imageList) { this.goToImage(it) }
        binding.imagesHorizontalList.adapter = previewAdapter
        previewAdapter.notifyDataSetChanged()
        Timber.d("onCreate: previewAdapter set, size=${imageList.size()}")

        binding.pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
            }

            override fun onPageSelected(position: Int) {
                Timber.d("onPageSelected: position=$position")
                binding.imagesHorizontalList.smoothScrollToPosition(position)
                previewAdapter.setSelectedItem(position)
            }

            override fun onPageScrollStateChanged(state: Int) {

            }
        })

        val currentPos = 0
        previewAdapter.setSelectedItem(currentPos)
        binding.pager.currentItem = currentPos
        Timber.d("onCreate: initial selection set to currentPos=$currentPos")

        if (imageList.size() == 0 && savedInstanceState == null) {
            Timber.d("onCreate: imageList empty, triggering camera")
            onTakePictureWithPermissionCheck()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.gallery, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_share).isVisible = !imageList.isEmpty
        menu.findItem(R.id.action_delete).isVisible = !imageList.isEmpty
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // ML Processing
            R.id.action_process_target -> {
                processCurrentImage()
                return true
            }

            // Share image (WhatsApp, etc.)
            R.id.action_share -> {
                val currentItem = binding.pager.currentItem
                shareImage(currentItem)
                return true
            }

            // Delete image
            R.id.action_delete -> {
                val currentItem = binding.pager.currentItem
                deleteImage(currentItem)
                return true
            }

            // Navigate up
            android.R.id.home -> {
                navigationController.finish()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun processCurrentImage() {
        if (imageList.isEmpty) return
        val idx = binding.pager.currentItem
        val file = File(filesDir, imageList[idx].fileName)
        if (!file.exists()) return

        // Avoid multiple runs
        if (mlJob?.isActive == true) return

        // Replace the commented coroutine with a simple background task that uses the mock processor.
        AsyncTask.execute {
            // Run pipeline (mock)
            val pipelineResult = MlTargetProcessor(applicationContext).runPipeline(file)
            if (pipelineResult == null) return@execute

            try {
                // Save image
                val outFile = File.createTempFile("img_proc", ".jpg", filesDir)
                FileOutputStream(outFile).use { fos ->
                    pipelineResult.processedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
                }

                // Post UI updates on main thread
                runOnUiThread {
                    imageList.addAll(listOf(outFile.name))  // OK

                    // Prepare normalized arrow positions for previous page
                    val flat = FloatArray(pipelineResult.arrows.size * 2)

                    // [(x1, y2), (x2, y2), ...] -> (x1, y1, x2, y2, ...)
                    pipelineResult.arrows.forEachIndexed { i, p ->
                        flat[i * 2] = p.x
                        flat[i * 2 + 1] = p.y
                    }

                    // Update adapters
                    updateResult(flat, finishAfter = true)
                    adapter?.notifyDataSetChanged()
                    previewAdapter.notifyDataSetChanged()

                    // Select new image
                    binding.pager.currentItem = imageList.size() - 1
                    //val resIntent = Intent()
                    //resIntent.putExtra(EXTRA_DETECTED_ARROWS, flat)
                    //navigationController.setResultSuccess(resIntent)

                    invalidateOptionsMenu()
                }
             } catch (e: IOException) {
                 Timber.e(e, "processCurrentImage: saving processed image failed")
             }
         }
    }

    private fun shareImage(currentItem: Int) {
        val currentImage = imageList[currentItem]
        val file = File(filesDir, currentImage.fileName)
        val exists = file.exists()
        Timber.d("shareImage: index=$currentItem, file=${file.absolutePath}, exists=$exists")
        val uri = file.toUri(this)
        Timber.d("shareImage: uri=$uri")
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "*/*"
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
    }

    private fun deleteImage(currentItem: Int) {
        MaterialDialog.Builder(this)
            .content(R.string.delete_image)
            .negativeText(android.R.string.cancel)
            .negativeColorRes(R.color.md_grey_500)
            .positiveText(R.string.delete)
            .positiveColorRes(R.color.md_red_500)
            .onPositive { _, _ ->
                imageList.remove(currentItem)
                updateResult()
                invalidateOptionsMenu()
                adapter!!.notifyDataSetChanged()
                val nextItem = (imageList.size() - 1).coerceAtMost(currentItem)
                previewAdapter.setSelectedItem(nextItem)
                binding.pager.currentItem = nextItem
            }
            .show()
    }

    /*private fun updateResult() {
        navigationController.setResultSuccess(imageList)
    }*/

    private fun updateResult(detectedArrows: FloatArray? = null, finishAfter: Boolean = false) {
        val resultIntent = Intent()
        resultIntent.putExtra(NavigationController.ITEM, imageList)
        resultIntent.putExtra(NavigationController.INTENT, intent?.extras)
        detectedArrows?.let { resultIntent.putExtra(EXTRA_DETECTED_ARROWS, it) }
        navigationController.setResultSuccess(resultIntent)

        if (finishAfter) {
            navigationController.finish() // returns to InputActivity with the result
        }
    }

    @NeedsPermission(Manifest.permission.CAMERA)
    internal fun onTakePicture() {
        EasyImage.openCameraForImage(this, 0)
    }

    @NeedsPermission(Manifest.permission.READ_MEDIA_IMAGES)
    internal fun onSelectImage() {
        EasyImage.openGallery(this, 0)
    }

    @SuppressLint("NeedOnRequestPermissionsResult")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        //super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        //GalleryActivityPermissionsDispatcher.onRequestPermissionsResult(this, requestCode, grantResults)
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Timber.d("onRequestPermissionsResult: requestCode=$requestCode, permissions=${permissions.contentToString()}, grants=${grantResults.contentToString()}")
        onRequestPermissionsResult(requestCode, grantResults)
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Timber.d("onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=${data != null}")
        EasyImage.handleActivityResult(requestCode, resultCode, data, this,
            object : DefaultCallback() {

                override fun onImagesPicked(
                    imageFiles: List<File>,
                    source: EasyImage.ImageSource,
                    type: Int
                ) {
                    Timber.d("onImagesPicked: count=${imageFiles.size}, source=$source, type=$type")
                    for (f in imageFiles) {
                        Timber.d("onImagesPicked: file=${f.absolutePath}, exists=${f.exists()}")
                    }
                    loadImages(imageFiles)
                }

                override fun onCanceled(source: EasyImage.ImageSource?, type: Int) {
                    //Cancel handling, you might wanna remove taken photo if it was canceled
                    if (source == EasyImage.ImageSource.CAMERA_IMAGE) {
                        val photoFile = EasyImage
                            .lastlyTakenButCanceledPhoto(applicationContext)
                        Timber.d("onCanceled: lastlyTakenButCanceledPhoto=${photoFile?.absolutePath}, exists=${photoFile?.exists()}")
                        photoFile?.delete()
                    }
                }
            })
    }

    private fun loadImages(imageFile: List<File>) {
        Timber.d("loadImages: start, incoming=${imageFile.size}")
        object : AsyncTask<Void, Void, List<String>>() {

            override fun doInBackground(vararg params: Void): List<String> {
                val internalFiles = ArrayList<String>()
                for (file in imageFile) {
                    try {
                        Timber.d("doInBackground: processing src=${file.absolutePath}, exists=${file.exists()}")
                        val internal = File.createTempFile("img", file.name, filesDir)
                        Timber.d("doInBackground: created temp dst=${internal.absolutePath}, exists=${internal.exists()}")
                        internalFiles.add(internal.name)
                        file.moveTo(internal)
                        Timber.d("doInBackground: moved ${file.absolutePath} -> ${internal.absolutePath}, existsSrc=${file.exists()}, existsDst=${internal.exists()}")
                    } catch (e: IOException) {
                        e.printStackTrace()
                        Timber.e(e, "doInBackground: move failed for ${file.absolutePath}")
                    }

                }
                return internalFiles
            }

            override fun onPostExecute(files: List<String>) {
                super.onPostExecute(files)
                Timber.d("onPostExecute: loaded=${files.size}, names=$files")
                imageList.addAll(files)
                Timber.d("onPostExecute: imageList.size=${imageList.size()}")
                updateResult()
                invalidateOptionsMenu()
                previewAdapter.notifyDataSetChanged()
                Timber.d("onPostExecute: previewAdapter notified")
                adapter!!.notifyDataSetChanged()
                Timber.d("onPostExecute: pager adapter notified")
                val currentPos = imageList.size() - 1
                previewAdapter.setSelectedItem(currentPos)
                binding.pager.currentItem = currentPos
                Timber.d("onPostExecute: set currentItem=$currentPos")
            }
        }.execute()
    }

    private fun goToImage(pos: Int) {
        Timber.d("goToImage: pos=$pos, imageList.size=${imageList.size()}")
        if (imageList.size() == pos) {
            Timber.d("goToImage: camera trigger at end")
            onTakePictureWithPermissionCheck()
        } else {
            binding.pager.setCurrentItem(pos, true)
            Timber.d("goToImage: setCurrentItem=$pos")
        }
    }

    companion object {
        const val EXTRA_IMAGES = "images"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DETECTED_ARROWS = "detected_arrows" // FloatArray [x1,y1,x2,y2,...] normalized

        fun getResult(data: Intent): ImageList {
            return data.getParcelableExtra(NavigationController.ITEM)!!
        }
    }


}
