package com.dajin.system.upload;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class UploadControllerTest {
    @Test void detectsImagesFromTheirContentInsteadOfTheClientFilename() {
        assertEquals("image/jpeg", UploadController.detectImage(new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, 0x00}).contentType());
        assertEquals("image/png", UploadController.detectImage(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}).contentType());
        assertEquals("image/gif", UploadController.detectImage("GIF89a-data".getBytes(StandardCharsets.US_ASCII)).contentType());
        assertEquals("image/webp", UploadController.detectImage("RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII)).contentType());
        assertEquals("image/heic", UploadController.detectImage(new byte[]{0, 0, 0, 0, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}).contentType());
    }

    @Test void rejectsTextEvenWhenItsFilenameCouldClaimToBeAnImage() {
        assertNull(UploadController.detectImage("plain text".getBytes(StandardCharsets.UTF_8)));
    }
}
