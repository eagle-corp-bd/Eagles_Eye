package com.eagleseye.camera.engine

import android.opengl.GLES30

class SeparableBlur(width: Int, height: Int) {
    private val hPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, FRAG_BLUR_H, "blur_h")
    private val vPass = ShaderPass(Shaders.VERTEX_FULLSCREEN, FRAG_BLUR_V, "blur_v")
    private val halfFBO = PingPongFBO(width / 2, height / 2, "blur_half")
    var radius = 8f

    /** Returns true only when both blur passes actually drew into [outputFbo]. */
    fun blur(inputTex: Int, outputFbo: Int, fullW: Int, fullH: Int): Boolean {
        if (!hPass.draw(inputTex, outputFbo = halfFBO.writeFbo, width = halfFBO.width, height = halfFBO.height) {
            setFloat("uRadius", radius)
            setFloat2("uResolution", halfFBO.width.toFloat(), halfFBO.height.toFloat())
        }) return false
        halfFBO.swap()
        if (!vPass.draw(halfFBO.readTex, outputFbo = outputFbo, width = fullW, height = fullH) {
            setFloat("uRadius", radius)
            setFloat2("uResolution", halfFBO.width.toFloat(), halfFBO.height.toFloat())
        }) return false
        return true
    }

    fun resize(w: Int, h: Int) {} // recreate if size changes significantly
    fun destroy() { hPass.destroy(); vPass.destroy(); halfFBO.destroy() }

    companion object {
        val FRAG_BLUR_H = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uResolution;
uniform float uRadius;
in vec2 vUV;
out vec4 fragColor;
void main(){
 vec2 off=vec2(uRadius/uResolution.x,0.);
 vec4 s=vec4(0.);
 s+=texture(uTexture,vUV-off*4.)*.05;s+=texture(uTexture,vUV-off*3.)*.09;
 s+=texture(uTexture,vUV-off*2.)*.12;s+=texture(uTexture,vUV-off)*.15;
 s+=texture(uTexture,vUV)*.18;s+=texture(uTexture,vUV+off)*.15;
 s+=texture(uTexture,vUV+off*2.)*.12;s+=texture(uTexture,vUV+off*3.)*.09;
 s+=texture(uTexture,vUV+off*4.)*.05;fragColor=s;
}"""
        val FRAG_BLUR_V = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform vec2 uResolution;
uniform float uRadius;
in vec2 vUV;
out vec4 fragColor;
void main(){
 vec2 off=vec2(0.,uRadius/uResolution.y);
 vec4 s=vec4(0.);
 s+=texture(uTexture,vUV-off*4.)*.05;s+=texture(uTexture,vUV-off*3.)*.09;
 s+=texture(uTexture,vUV-off*2.)*.12;s+=texture(uTexture,vUV-off)*.15;
 s+=texture(uTexture,vUV)*.18;s+=texture(uTexture,vUV+off)*.15;
 s+=texture(uTexture,vUV+off*2.)*.12;s+=texture(uTexture,vUV+off*3.)*.09;
 s+=texture(uTexture,vUV+off*4.)*.05;fragColor=s;
}"""
    }
}
