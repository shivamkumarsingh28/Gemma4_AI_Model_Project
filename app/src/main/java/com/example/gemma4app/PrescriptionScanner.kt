package com.example.gemma4app

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class PrescriptionScanner(private val context: Context) {
    fun scanPrescription(uri: Uri, onResult: (String) -> Unit) {
        try {
            Log.d("DrAI", "Starting OCR for URI: $uri")
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            // InputImage.fromFilePath can handle content:// uris
            val image = InputImage.fromFilePath(context, uri)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    // Iterate through blocks to preserve some structure and filter noise
                    val sb = StringBuilder()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val lineText = line.text.trim()
                            // Filter out very short noise (like single random characters)
                            if (lineText.length > 1) {
                                sb.append(lineText).append("\n")
                            }
                        }
                        sb.append("\n") // Space between blocks
                    }
                    
                    val finalText = sb.toString().trim()
                    Log.d("DrAI", "OCR Success. Structured Length: ${finalText.length}")
                    
                    if (finalText.isNotBlank()) {
                        onResult(finalText)
                    } else {
                        onResult("Error: No readable text found. Ensure the photo is clear and contains a prescription.")
                    }
                }
                .addOnFailureListener { e ->
                    Log.e("DrAI", "OCR Failure", e)
                    onResult("Error scanning prescription: ${e.localizedMessage ?: "Unknown error"}")
                }
        } catch (e: Exception) {
            Log.e("DrAI", "Image loading error", e)
            onResult("Error loading image: ${e.localizedMessage ?: "Could not open file"}")
        }
    }
}
