package com.eagleseye.camera.engine

object Shaders {
    // NOTE: must start at "#version" with no leading newline — Mali rejects
    // a blank first line. ShaderPass.normalizeGlsl also strips any prefix.
    const val VERTEX_FULLSCREEN = """#version 300 es
layout(location = 0) in vec2 aPosition;
out vec2 vUV;
void main(){
 vUV=aPosition*.5+.5;
 gl_Position=vec4(aPosition,0.,1.);
}"""

    val FRAG_OES_TO_2D = """
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uTexture;
in vec2 vUV;
out vec4 fragColor;
void main(){fragColor=texture(uTexture,vUV);}"""

    const val FRAG_FISHEYE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uStrength;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 uv=vUV*2.-1.;uv.x*=uResolution.x/uResolution.y;float r=length(uv);
 if(r>1.){fragColor=vec4(0.);return;}
 float k=uStrength*1.2;float s=(1.+k*r*r)/(1.+k);
 vec2 d=uv*s;d.x/=uResolution.x/uResolution.y;d=d*.5+.5;
 vec3 col=texture(uTexture,d).rgb;
 float vig=1.-r*r*.5*uStrength;col*=clamp(vig,0.,1.);
 fragColor=vec4(col,1.);
}"""

    const val FRAG_VIGNETTE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uRadius,uSoftness,uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 c=(vUV-.5)*vec2(uResolution.x/uResolution.y,1.);float d=length(c);
 float vig=1.-smoothstep(uRadius,uRadius+uSoftness,d)*uIntensity;
 vec4 col=texture(uTexture,vUV);fragColor=vec4(col.rgb*vig,col.a);
}"""

    const val FRAG_CA = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uStrength;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 d=(vUV-.5)*uStrength*.025;
 float r=texture(uTexture,vUV+d).r;vec4 g=texture(uTexture,vUV);float b=texture(uTexture,vUV-d).b;
 fragColor=vec4(r,g.g,b,g.a);
}"""

    const val FRAG_GRAIN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uFrameSeed,uIntensity,uSize;
in vec2 vUV;out vec4 fragColor;
float ign(vec2 p){return fract(52.9829189*fract(dot(p,vec2(0.06711056,0.00583715))));}
void main(){
 vec4 c=texture(uTexture,vUV);float l=dot(c.rgb,vec3(.2126,.7152,.0722));
 float w=clamp(1.-abs(l-.5)*1.8,.15,1.);float n=ign(vUV*uResolution/uSize+uFrameSeed*17.)-.5;
 fragColor=vec4(c.rgb+n*.12*uIntensity*w,c.a);
}"""

    const val FRAG_DUST = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uSeed,uIntensity;
in vec2 vUV;out vec4 fragColor;
float hash(vec2 p){return fract(sin(dot(p,vec2(41.3,289.1)))*43758.5453);}
void main(){
 vec4 c=texture(uTexture,vUV);vec2 cell=floor(vUV*vec2(60.,90.));
 float d=step(.997,hash(cell+uSeed))*(hash(cell*1.7)*.6+.4);
 float col=floor(vUV.x*40.);float sc=step(.985,hash(vec2(col,floor(uSeed))))*(1.-smoothstep(0.,.0015,abs(fract(vUV.x*40.)-.5)-.001));
 float mark=clamp(d+sc,0.,1.)*uIntensity;fragColor=vec4(mix(c.rgb,vec3(1.),mark*.7),c.a);
}"""

    // VHS — full rewrite. The geometry is now subtle and realistic: a faint
    // sub-pixel jitter over the whole frame plus a crisp tear confined to a
    // narrow head-switch band at the bottom. Nothing bends or drags the top of
    // the frame. The heavier, intentional VHS look (grade, scanlines, chroma
    // bleed, CA, noise, dropouts, head shudder, posterization) is preserved.
    const val FRAG_VHS = """
#version 300 es
precision mediump float;

uniform sampler2D uTexture;
uniform vec2 uResolution;
uniform float uTime;
uniform float uIntensity;
uniform float uTracking;
uniform float uChromaBleed;
uniform float uNoise;
uniform float uScanline;
uniform float uWobble;
uniform float uVignette;
uniform float uColorShift;
uniform float uHeadSwitch;
uniform float uBlur;

in vec2 vUV;
out vec4 fragColor;

// ─── Noise (raw hash only — no smooth gradient needed at these scales) ───
float hh(vec2 p){p=fract(p*vec2(443.9,397.3));p+=dot(p,p+19.2);return fract(p.x*p.y);}

// ─── Whole-frame jitter: engages in short random bursts, always tiny ───
vec2 doGeo(vec2 uv, float t) {
    float sd=floor(t*0.8);
    float on=step(0.55,hh(vec2(sd,7.7)));      // ~45% of the time there is any wobble
    float jx=(hh(vec2(uv.y*600.,sd*3.1))-.5)*.0012*uWobble*on;
    float jy=(hh(vec2(uv.y*600.+9.,sd*3.1))-.5)*.0004*uWobble*on;
    return vec2(uv.x+jx,uv.y+jy);
}

// ─── Single tear at a time: random place, random (tiny) size, appears and
//     disappears on its own with a soft in/out life window ───
float doGeoTear(vec2 uv, float t) {
    float ep=floor(t/1.8);                     // a new tear episode every ~1.8s
    float e0=hh(vec2(ep,1.3));
    float on=step(0.5,e0);                     // only ~half of the episodes tear
    float ph=fract(t*0.85+e0*3.7);             // life inside the episode
    float life=step(0.2,ph)*smoothstep(1.,.5,ph);      // pop in, fade out
    float sy=0.86+e0*0.10;                     // random spot near the bottom
    float sw=0.012+hh(vec2(ep,9.9))*0.020;     // random tiny band width
    float band=smoothstep(sw*2.,0.,abs(uv.y-sy));
    float s=hh(vec2(uv.x*110.,e0*37.))*2.-1.;
    return band*s*0.0012*uHeadSwitch*life;
}

// ─── Head shudder: thin, slow, always near the very bottom ───
float doHeadShudder(vec2 uv, float t) {
    float sy=0.97+0.01*sin(t*.31)+0.006*sin(t*1.7);
    float band=exp(-pow((uv.y-sy)*160.,2.));
    float s=(hh(vec2(uv.x*240.,t*3.))*2.-1.)*.10;
    return band*s*uHeadSwitch;
}

// ─── Dropouts (thin horizontal lines) ───
float doDropout(vec2 uv, float t) {
    float l=floor(uv.y*300.),h=hh(vec2(l,floor(t*0.15))),p=step(0.97,h)*uTracking;
    float xp=fract(uv.x*80.+hh(vec2(l,15.)));
    return p*step(0.5,abs(xp-0.5))*0.6;
}

// ─── RF noise (raw hash on a coarse grid — looks static, costs almost nothing) ───
float doRF(vec2 uv, float t) { return (hh(vec2(floor(uv.x*300.),floor(uv.y*300.)+floor(t*18.)))-.5)*.09*uNoise; }

// ─── Combined Y/C pass: ONE loop of 9 taps feeds both the luma blur and the
//     chroma bleed from the same fetched texels (was 11+9 taps + smooth noise:
//     this is the single biggest mobile-GPU win in the whole shader). ───
vec3 blurYC(vec2 uv, float t) {
    float bl=uChromaBleed*.02, lo=uBlur*.015;
    float o=max(bl,lo);
    float useL=step(.01,uBlur), useC=step(.01,uChromaBleed);
    vec3 cu=texture(uTexture,uv).rgb;
    float y=dot(cu,vec3(.299,.587,.114));
    float i=.596*cu.r-.274*cu.g-.322*cu.b,q=.211*cu.r-.523*cu.g+.312*cu.b;
    float ys=y*(.30*useL+1.-useL),is=i*.22*useC,qs=q*.22*useC;
    // shared taps: luma weights fall off with uBlur, chroma with uChromaBleed
    for(int j=1;j<=4;j++){
        float fj=float(j);
        vec3 a=texture(uTexture,uv+vec2(o*fj,0)).rgb;
        vec3 b=texture(uTexture,uv-vec2(o*fj,0)).rgb;
        float wL=.16*useL/(fj*.5+1.);
        float wC=.14*useC/(fj*.55+1.);
        ys+=wL*(dot(a,vec3(.299,.587,.114))+dot(b,vec3(.299,.587,.114)));
        is+=wC*(.596*a.r-.274*a.g-.322*a.b+.596*b.r-.274*b.g-.322*b.b);
        qs+=wC*(.211*a.r-.523*a.g+.312*a.b+.211*b.r-.523*b.g+.312*b.b);
    }
    // tapered chroma noise rides the bleed signal
    float cn=(hh(vec2(uv.y*80.,floor(t*1.7)))-.5)*.10*uChromaBleed;
    float iq=is+cn*.5,qq=qs+cn*.5;
    return clamp(vec3(ys+.956*iq+.621*qq,ys-.272*iq-.647*qq,ys-1.106*iq+1.703*qq),0.,1.);
}

// ─── Y/C delay (bilateral: never drags the image to one side) ───
vec3 doYCDelay(vec2 uv, float t) {
    float d=uChromaBleed*.005;
    vec3 c=texture(uTexture,uv).rgb;
    vec3 cr=texture(uTexture,uv+vec2(d,0)).rgb;
    vec3 cl=texture(uTexture,uv-vec2(d,0)).rgb;
    float el=dot(c,vec3(.299,.587,.114));
    float er=dot(cr,vec3(.299,.587,.114));
    float elft=dot(cl,vec3(.299,.587,.114));
    float dr=abs(el-er),dl=abs(el-elft);
    vec3 outv=dr>dl?cr:cl;
    return mix(c,outv,clamp(max(dr,dl)*8.,0.,1.)*uChromaBleed*.6);
}

// ─── CA (slowed 3x, symmetric channels) ───
vec3 doCA(vec2 uv, float t) {
    float sh=uColorShift*.012,j=sin(t*0.7+uv.y*25.)*sh*.4;
    float offL=sh+j,offR=sh-j*.4;
    return vec3(texture(uTexture,uv+vec2(offL,0)).r,texture(uTexture,uv).g,texture(uTexture,uv-vec2(offR,0)).b);
}

// ─── Scanlines ───
float doScanline(vec2 uv) { float sc=sin(uv.y*uResolution.y*3.14159)*.5+.5; return mix(1.,.55+.45*sc,uScanline); }

// ─── Vignette ───
float doVig(vec2 uv) { vec2 c=(uv-.5)*1.6; return mix(1.,1.-dot(c,c),uVignette); }

// ─── Color grade ───
vec3 doGrade(vec3 col, float t) {
    col=mix(col,col*.88+.07,uIntensity);
    col.rb*=vec2(1.08,.92);
    float l=dot(col,vec3(.299,.587,.114));
    col=mix(vec3(pow(l,.85)),col,1.25);
    col=clamp(pow(max(col,0.),vec3(.88)),0.,1.);
    return col;
}

// ─── Macrovision pulse (slowed 3x) ───
float doAGC(float t) { return 1.+.04*sin(t*0.57)+.025*sin(t*1.13); }

vec3 quant(vec3 c,float st){return floor(c*st+.5)/st;}

void main() {
    vec2 uv=vUV;float t=uTime;

    // Geometry — random tiny tear that pops in/out + rare micro jitter only
    uv=doGeo(uv,t);
    uv.x+=doGeoTear(uv,t)+doHeadShudder(uv,t);

    // Sample + CA
    vec3 col=doCA(uv,t);

    // Y/C delay
    col=doYCDelay(uv,t);

    // Luma + chroma blur in one shared tap loop
    if(uBlur>.01||uChromaBleed>.01)col=blurYC(uv,t);

    // Noise layers
    col+=vec3(doRF(uv,t));
    col+=vec3(doDropout(uv,t));

    // Scanlines + vignette
    col*=doScanline(uv)*doVig(uv);

    // Grade
    col=doGrade(col,t)*doAGC(t);

    // Posterization
    if(uIntensity>.3)col=mix(col,quant(col,12.),(uIntensity-.3)*1.6);

    fragColor=vec4(clamp(col,0.,1.),1.);
}"""

    const val FRAG_KALEIDO = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uSlices,uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 c=texture(uTexture,vUV).rgb;
 float n=max(2.,floor(uSlices));
 float sec=6.28318530718/max(n,1.);
 vec2 p=vUV-.5;
 float r=length(p);
 float a=atan(p.y,p.x);
 float fa=mod(a,sec);
 float na=sec*.5-abs(fa-sec*.5);
 vec2 q=vec2(cos(na),sin(na))*r+.5;
 vec3 k=texture(uTexture,clamp(q,vec2(0.),vec2(1.))).rgb;
 fragColor=vec4(mix(c,k,uIntensity),1.);
}"""

    const val FRAG_PRISM = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uAngle,uOffset,uOpacity;
uniform vec3 uTint1,uTint2;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 d=vec2(cos(uAngle),sin(uAngle));
 vec4 src=texture(uTexture,vUV);
 float r=texture(uTexture,vUV+d*uOffset).r;
 float g=texture(uTexture,vUV+d*uOffset*.5).g;
 float b=texture(uTexture,vUV-d*uOffset*.5).b;
 vec3 prism=vec3(r,g,b);
 prism=mix(prism,prism*uTint1,.5);
 vec3 c=mix(src.rgb,prism,uOpacity);
 fragColor=vec4(c,src.a);
}"""

    const val FRAG_DUOTONE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec3 uShadowColor,uHighlightColor;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);float l=dot(c.rgb,vec3(.2126,.7152,.0722));
 vec3 t=mix(uShadowColor,uHighlightColor,l);fragColor=vec4(mix(c.rgb,t,uIntensity),c.a);
}"""

    const val FRAG_BLEACH = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);float l=dot(c.rgb,vec3(.2126,.7152,.0722));
 vec3 x=(vec3(l)-.5)*1.6+.5;vec3 r=mix(c.rgb,x,.5);fragColor=vec4(mix(c.rgb,r,uIntensity),c.a);
}"""

    const val FRAG_CROSS = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
float crv(float x,float l,float g){return pow(mix(x,x+l*(1.-x),.5),g);}
void main(){
 vec4 c=texture(uTexture,vUV);vec3 r=vec3(crv(c.r,.02,1.05),crv(c.g,.08,.85),crv(c.b,-.05,1.2));
 fragColor=vec4(mix(c.rgb,r,uIntensity),c.a);
}"""

    const val FRAG_VELVIA = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 r=c.rgb;
 float l=dot(r,vec3(.2126,.7152,.0722));
 r=mix(vec3(l),r,1.32);
 r=pow(r,vec3(.94));
 r=mix(r,r*vec3(1.07,.995,.93),1.);
 r=mix(r,clamp((r-.05)*1.05+.05,0.,1.),1.);
 fragColor=vec4(mix(c.rgb,r,uIntensity),c.a);
}"""

    const val FRAG_PORTRA800 = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 r=c.rgb;
 float l=dot(r,vec3(.2126,.7152,.0722));
 r=pow(r,vec3(1.09));
 r=r*vec3(1.05,1.0,.955)+vec3(.022,.017,.012);
 r=mix(vec3(l),r,1.14);
 fragColor=vec4(mix(c.rgb,r,uIntensity),c.a);
}"""

    const val FRAG_WINTER = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 r=c.rgb;
 float l=dot(r,vec3(.2126,.7152,.0722));
 r=mix(vec3(l),r,.78);
 r=mix(r,r*vec3(.97,1.0,1.09),1.);
 r=r*1.07+.018;
 r=clamp(r,0.,1.);
 fragColor=vec4(mix(c.rgb,r,uIntensity),c.a);
}"""

    const val FRAG_OBSIDIAN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 r=c.rgb;
 float l=dot(r,vec3(.2126,.7152,.0722));
 r=(r-.5)*1.3+.5;
 r=pow(r,vec3(1.18));
 r*=smoothstep(0.,.09,l);
 vec2 uv=vUV-.5;float d=length(uv)*1.5;
 r*=1.-d*d*.5;
 r=clamp(r,0.,1.);
 fragColor=vec4(mix(c.rgb,r,uIntensity),c.a);
}"""

    const val FRAG_WARP = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uZoom,uRotation;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 uv=vUV*2.-1.;uv.x*=uResolution.x/uResolution.y;float r=length(uv);
 float a=atan(uv.y,uv.x)+uRotation*4.*(1.-r/1.414);
 float rD=r*(1.+uZoom*3.*r*r);
 vec2 s=vec2(cos(a),sin(a))*rD;
 if(length(s)>1.6){fragColor=vec4(0.);return;}
 s.x/=uResolution.x/uResolution.y;s=s*.5+.5;
 fragColor=texture(uTexture,clamp(s,0.,1.));
}"""

    const val FRAG_BRIGHT_PASS = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uThreshold;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);float l=dot(c.rgb,vec3(.2126,.7152,.0722));
 float w=max(l-uThreshold,0.)/max(l,.0001);fragColor=vec4(c.rgb*w,1.);
}"""

    const val FRAG_BLOOM_COMP = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 s=texture(uTexture,vUV);vec4 b=texture(uTexture2,vUV);
 fragColor=vec4(s.rgb+b.rgb*uIntensity,s.a);
}"""

    const val FRAG_HALATION_COMP = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform vec3 uTint;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 s=texture(uTexture,vUV);vec4 h=texture(uTexture2,vUV);
 fragColor=vec4(s.rgb+h.rgb*uTint*uIntensity,s.a);
}"""

    const val FRAG_MIST = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform vec2 uResolution;uniform vec3 uMistColor;uniform float uHorizonY,uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 float uvY=vUV.y;vec4 s=texture(uTexture,vUV);vec4 h=texture(uTexture2,vUV);
 float df=smoothstep(0.,1.,uvY/max(uHorizonY,.001));
 vec3 l=mix(s.rgb,uMistColor,.15*uIntensity);
 vec3 r=mix(l,mix(h.rgb,uMistColor,.4),df*uIntensity);fragColor=vec4(r,s.a);
}"""

    const val FRAG_DREAMY = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 s=texture(uTexture,vUV);vec4 b=texture(uTexture2,vUV);
 // soft white glow blended with a gentle bloom lift
 vec3 glow=mix(s.rgb,b.rgb,.35);
 vec3 base=mix(s.rgb,glow*1.06,.55*uIntensity);
 // lift shadows + warm the mids so the scene dreams away
 vec3 soft=s.rgb*.93+.045;
 vec3 w=vec3(1.02,1.005,.985);
 vec3 mixed=mix(base,soft*.5+glow*.5,uIntensity*.55);
 mixed=mix(mixed,mixed*w,uIntensity*.28);
 fragColor=vec4(mixed,s.a);
}"""

    const val FRAG_TILT_SHIFT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform vec2 uResolution;uniform float uFocusY,uFocusWidth,uFeather;
in vec2 vUV;out vec4 fragColor;
void main(){
 float b=smoothstep(uFocusWidth,uFocusWidth+uFeather,abs(vUV.y-uFocusY));
 vec4 s=texture(uTexture,vUV);vec4 bl=texture(uTexture2,vUV);fragColor=mix(s,bl,b);
}"""

    const val FRAG_ANAMORPHIC = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uStreakLength;uniform vec3 uTint;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 s=vec3(0.);const int N=12;
 for(int i=0;i<N;i++){float t=float(i)/float(N-1);float o=(t-.5)*uStreakLength;s+=texture(uTexture,vec2(vUV.x+o,vUV.y)).rgb*(1.-abs(t-.5)*2.);}
 fragColor=vec4(s/(float(N)*.5)*uTint,1.);
}"""

const val FRAG_ANAMORPHIC_STREAK = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uLightPos;uniform float uIntensity,uAnamorphic,uTime;
in vec2 vUV;out vec4 fragColor;
float gst(vec2 p,float s,vec2 uv){return smoothstep(s,0.,length(uv-p));}
void main(){
 vec4 c=texture(uTexture,vUV);vec3 o=c.rgb;
 vec3 l=texture(uTexture,uLightPos).rgb;
 float lm=dot(l,vec3(.2126,.7152,.0722));
 float a=smoothstep(.15,.7,lm)*uIntensity;
  if(a>.001){
   vec2 cn=vec2(.5);vec2 dr=normalize(cn-uLightPos);float dd=distance(cn,uLightPos);
   float wb=dot(l,vec3(1.,.5,.1))/max(dot(l,vec3(.1,.5,1.)),.001);
   float wf=clamp((wb-1.)*.5,0.,1.);
   vec3 f=vec3(0.);
   for(int i=0;i<7;i++){
    float t=float(i)*.22-.12;vec2 po=uLightPos+dr*dd*t;
    float sz=.035/(1.+abs(t)*1.2);float ca=.003*(1.+abs(t));
    float r=gst(po,sz,vUV+vec2(ca,0.));float g=gst(po,sz,vUV);float b=gst(po,sz,vUV-vec2(ca,0.));
    float wc=mod(float(i),2.);vec3 gc=mix(vec3(.3,.6,1.),vec3(1.,.85,.5),wc);
    f+=vec3(r,g,b)*mix(gc,vec3(1.,.7,.3),wf)*.7;
   }
   if(uAnamorphic>.5){
    vec2 dv=vUV-uLightPos;
    float sx=exp(-abs(dv.x)*20.);
    f+=mix(vec3(.15,.4,1.),vec3(1.,.6,.1),wf)*sx*exp(-abs(dv.y-.008)*200.)*.15*a;
    f+=mix(vec3(1.,.6,.1),vec3(.15,.4,1.),wf)*sx*exp(-abs(dv.y+.008)*200.)*.10*a;
   }
  vec2 rv=vUV-uLightPos;float rl=length(rv);float ag=atan(rv.y,rv.x)+uTime*.1;
  f+=vec3(1.,.95,.9)*pow(abs(sin(ag*3.)),20.)*exp(-rl*5.)*.05*a;
  f+=vec3(1.,.97,.9)*exp(-rl*rl*5.)*.12*a;
  o+=f;
 }
 fragColor=vec4(o,c.a);
}"""

    // Streak — spec 5.4 of the repo guide: a bright-pass field is sampled
    // horizontally with 12 taps and a triangular falloff (1-|t-.5|*2), divided
    // by SAMPLES*0.5, tinted and added over the frame. The bright pass itself
    // is inlined: threshold-excess luminance (Kino-style), softened vertically
    // first so individual hot pixels glue into continuous rods.
    // KinoStreak (MIT, Keijiro Takahashi — github.com/keijiro/KinoStreak).
    // Faithful port of Streak.cginc: prefilter (vertical 2-tap + threshold),
    // 6-tap horizontal box downsample (hscale 1.25, taps ±1/±3/±5 texels),
    // stretch upsample, then additive tinted composite (color * intensity * 5).
    const val FRAG_STREAK = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uThreshold,uIntensity,uStretch;uniform vec3 uTint;
in vec2 vUV;out vec4 fragColor;
vec3 pre(vec2 uv){
 vec2 d=vec2(0.,1.5/uResolution.y*.5);
 vec3 c=(texture(uTexture,uv+d).rgb+texture(uTexture,uv-d).rgb)*.5;
 float br=max(c.r,max(c.g,c.b));
 return c*max(0.,br-uThreshold)/max(br,1e-5);
}
void main(){
 vec3 src=texture(uTexture,vUV).rgb;
 float dx=1.25/uResolution.x;
 vec3 low=(pre(vUV+vec2(dx*5.,0.))+pre(vUV+vec2(dx*3.,0.))+pre(vUV+vec2(dx,0.))
          +pre(vUV+vec2(-dx,0.))+pre(vUV+vec2(-dx*3.,0.))+pre(vUV+vec2(-dx*5.,0.)))/6.;
 vec3 up=mix(src,low,uStretch);
 vec3 cf=up*uTint*uIntensity*5.;
 fragColor=vec4(src+cf,1.);
}"""

    // 8-bit fallback variants of prefilter/composite. When FP16 render targets
    // are unavailable (old Mali/Adreno, ANGLE), storing the pyramid in sqrt-
    // companded space preserves the faint streak tails in 8-bit — the box and
    // lerp passes run unchanged on the companded values, and the composite
    // squares back before the repo's tint*intensity*5 add.
    const val FRAG_KINO_PREFILTER_SQRT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uThreshold;
in vec2 vUV;out vec4 fragColor;
void main(){
 const float vscale=1.5;
 float dy=vscale/uResolution.y*.5;
 vec3 c0=texture(uTexture,vec2(vUV.x,vUV.y-dy)).rgb;
 vec3 c1=texture(uTexture,vec2(vUV.x,vUV.y+dy)).rgb;
 vec3 c=(c0+c1)*.5;
 float br=max(c.r,max(c.g,c.b));
 c*=max(0.,br-uThreshold)/max(br,1e-5);
 fragColor=vec4(sqrt(max(c,0.)),1.);
}"""

    const val FRAG_KINO_COMPOSITE_SQRT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;
uniform vec3 uColor;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 st=texture(uTexture,vUV).rgb;
 st=st*st;
 vec3 src=texture(uTexture2,vUV).rgb;
 fragColor=vec4(st*uColor*uIntensity*5.+src,1.);
}"""

    // SUN variant prefilter: same repo math, radial mask around the detected
    // brightest source (the sun) so only it produces streaks. Output stored
    // linear (FP16) — _SQRT twin stores sqrt-companded for 8-bit fallback.
    const val FRAG_KINO_PREFILTER_SUN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uThreshold;uniform vec2 uSunPos;uniform float uSunSize;
in vec2 vUV;out vec4 fragColor;
void main(){
 const float vscale=1.5;
 float dy=vscale/uResolution.y*.5;
 vec3 c0=texture(uTexture,vec2(vUV.x,vUV.y-dy)).rgb;
 vec3 c1=texture(uTexture,vec2(vUV.x,vUV.y+dy)).rgb;
 vec3 c=(c0+c1)*.5;
 float br=max(c.r,max(c.g,c.b));
 c*=max(0.,br-uThreshold)/max(br,1e-5);
 float d=distance(vUV,uSunPos);
 c*=smoothstep(uSunSize,uSunSize*.25,d);
 fragColor=vec4(c,1.);
}"""

    const val FRAG_KINO_PREFILTER_SUN_SQRT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uThreshold;uniform vec2 uSunPos;uniform float uSunSize;
in vec2 vUV;out vec4 fragColor;
void main(){
 const float vscale=1.5;
 float dy=vscale/uResolution.y*.5;
 vec3 c0=texture(uTexture,vec2(vUV.x,vUV.y-dy)).rgb;
 vec3 c1=texture(uTexture,vec2(vUV.x,vUV.y+dy)).rgb;
 vec3 c=(c0+c1)*.5;
 float br=max(c.r,max(c.g,c.b));
 c*=max(0.,br-uThreshold)/max(br,1e-5);
 float d=distance(vUV,uSunPos);
 c*=smoothstep(uSunSize,uSunSize*.25,d);
 fragColor=vec4(sqrt(max(c,0.)),1.);
}"""

    // SUN variant composite: streak sample vertically bent near the frame edges
    // (uWarp) — the subtle anamorphic lens bend. Linear (FP16) twin.
    const val FRAG_KINO_COMPOSITE_WARP = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;
uniform vec3 uColor;uniform float uIntensity;uniform float uWarp;
in vec2 vUV;out vec4 fragColor;
void main(){
 float bend=4.*vUV.x*(1.-vUV.x)-1.;
 vec2 uv=vec2(vUV.x,vUV.y+uWarp*bend);
 vec3 st=texture(uTexture,uv).rgb;
 vec3 src=texture(uTexture2,vUV).rgb;
 fragColor=vec4(st*uColor*uIntensity*5.+src,1.);
}"""

    const val FRAG_KINO_COMPOSITE_WARP_SQRT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;
uniform vec3 uColor;uniform float uIntensity;uniform float uWarp;
in vec2 vUV;out vec4 fragColor;
void main(){
 float bend=4.*vUV.x*(1.-vUV.x)-1.;
 vec2 uv=vec2(vUV.x,vUV.y+uWarp*bend);
 vec3 st=texture(uTexture,uv).rgb;
 st=st*st;
 vec3 src=texture(uTexture2,vUV).rgb;
 fragColor=vec4(st*uColor*uIntensity*5.+src,1.);
}"""

    // KinoStreak (MIT, Keijiro Takahashi — github.com/keijiro/KinoStreak). Faithful GLES3 port of Streak.cginc's four passes
    // in CameraGLRenderer.renderKinoStreak: prefilter (half-height) → 6-tap
    // horizontal box downsampling (width halved per level) → stretch upsample
    // combine → additive tinted composite (color * intensity * 5 + source).
    const val FRAG_KINO_PREFILTER = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uThreshold;
in vec2 vUV;out vec4 fragColor;
void main(){
 const float vscale=1.5;
 float dy=vscale/uResolution.y*.5;
 vec3 c0=texture(uTexture,vec2(vUV.x,vUV.y-dy)).rgb;
 vec3 c1=texture(uTexture,vec2(vUV.x,vUV.y+dy)).rgb;
 vec3 c=(c0+c1)*.5;
 float br=max(c.r,max(c.g,c.b));
 c*=max(0.,br-uThreshold)/max(br,1e-5);
 fragColor=vec4(c,1.);
}"""

    // HDR headroom emulation: KinoStreak runs on HDR frames (demo emitter is
    // ~9x white); a phone viewfinder is LDR (<=1.0). Selective gain: only true
    // highlights (br > 0.5) are boosted up to 9x — midtones stay at 1x and fall
    // under the repo threshold 1.0, exactly like HDR scene content does.
    const val FRAG_KINO_GAIN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 c=texture(uTexture,vUV).rgb;
 float br=max(c.r,max(c.g,c.b));
 float t=clamp((br-.5)/.5,0.,1.);
 float k=t*t*(3.-2.*t);
 fragColor=vec4(c*(1.+8.*k),1.);
}"""

    // Debug monitor: shows the raw streak accumulator (red channel) in a corner
    // inset of the live viewfinder so the streak buffer can be inspected while
    // tuning. Shows a horizontal bright line when the pyramid works.
    const val FRAG_KINO_MONITOR = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
in vec2 vUV;out vec4 fragColor;
void main(){
 float s=texture(uTexture,vUV).r;
 fragColor=vec4(vec3(s),1.);
}"""

    const val FRAG_KINO_DOWN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
in vec2 vUV;out vec4 fragColor;
void main(){
 const float hscale=1.25;
 float dx=hscale/uResolution.x;
 vec3 s=vec3(0.);
 s+=texture(uTexture,vec2(vUV.x-dx*5.,vUV.y)).rgb;
 s+=texture(uTexture,vec2(vUV.x-dx*3.,vUV.y)).rgb;
 s+=texture(uTexture,vec2(vUV.x-dx*1.,vUV.y)).rgb;
 s+=texture(uTexture,vec2(vUV.x+dx*1.,vUV.y)).rgb;
 s+=texture(uTexture,vec2(vUV.x+dx*3.,vUV.y)).rgb;
 s+=texture(uTexture,vec2(vUV.x+dx*5.,vUV.y)).rgb;
 fragColor=vec4(s/6.,1.);
}"""

    const val FRAG_KINO_UP = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;
uniform float uStretch;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 coarse=texture(uTexture,vUV).rgb;
 vec3 fine=texture(uTexture2,vUV).rgb;
 fragColor=vec4(mix(fine,coarse,uStretch),1.);
}"""

    const val FRAG_KINO_COMPOSITE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;
uniform vec3 uColor;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 st=texture(uTexture,vUV).rgb;
 vec3 src=texture(uTexture2,vUV).rgb;
 fragColor=vec4(st*uColor*uIntensity*5.+src,1.);
}"""

    // Kino Sharpen (MIT, Keijiro Takahashi): 3x3 unsharp mask.
    const val FRAG_SHARPEN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
vec4 S(vec2 uv){return texture(uTexture,clamp(uv,vec2(0.),vec2(1.)));}
void main(){
 vec2 t=1./uResolution;
 vec4 c0=S(vUV+vec2(-t.x,-t.y));vec4 c1=S(vUV+vec2(0.,-t.y));vec4 c2=S(vUV+vec2(t.x,-t.y));
 vec4 c3=S(vUV+vec2(-t.x,0.));vec4 c4=S(vUV);vec4 c5=S(vUV+vec2(t.x,0.));
 vec4 c6=S(vUV+vec2(-t.x,t.y));vec4 c7=S(vUV+vec2(0.,t.y));vec4 c8=S(vUV+vec2(t.x,t.y));
 fragColor=vec4((c4-(c0+c1+c2+c3-8.*c4+c5+c6+c7+c8)*uIntensity).rgb,c4.a);
}"""

    // Tune: brightness / contrast / saturation / warmth / highlights / shadows /
    // ambience in one pass. Anchored so the neutral value is 1.0 (brightness,
    // contrast, saturation) or 0.0 (warmth / highlights / shadows / ambience).
    const val FRAG_EDIT_TUNE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uBrightness,uContrast,uSaturation,uWarmth,uHighlights,uShadows,uAmbience,uExposure,uWhites,uBlacks,uVibrance;
in vec2 vUV;out vec4 fragColor;
float lum(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
void main(){
 vec3 c=texture(uTexture,vUV).rgb;
 c*=max(uBrightness,.05);
 c*=pow(2.,uExposure*1.3);
 float l=lum(c);
 l=(l-.5)*uContrast+.5;
 float shm=1.-smoothstep(.05,.5,l);
 float him=smoothstep(.5,.95,l);
 l=l+shm*uShadows*.18+him*uHighlights*.18;
 float wm=smoothstep(.55,1.,l);
 float bm=smoothstep(0.,.45,1.-l);
 l=l+wm*uWhites*.24+bm*uBlacks*.2;
 float amb=max(0.,uAmbience);
 l=l*(1.-amb*.22)+amb*.12;
 c=mix(vec3(l),c,clamp(1.+uSaturation,0.,3.));
 float mx2=max(max(c.r,c.g),c.b),mn2=min(min(c.r,c.g),c.b);
 float cs=(mx2-mn2)/max(mx2+mn2,1e-5);
 float vs=1.+uVibrance*(1.-clamp(cs*1.6,0.,1.));
 c=mix(vec3(lum(c)),c,clamp(vs,0.,2.5));
 float r=c.r*(1.+uWarmth*.25),g=c.g*(1.-uWarmth*.05),b=c.b*(1.-uWarmth*.22);
 float m=max(max(r,g),b);if(m>1.){r/=m;g/=m;b/=m;}
 fragColor=vec4(clamp(vec3(r,g,b),0.,1.),1.);
}"""


    // ── Pro editor: film stock grade (sat / contrast / lift / rolloff),
    //    exact GL port of EdCpuEngine.filmGrade (photoncam applyGradeAndRolloff). ──
    const val FRAG_EDIT_FILM_GRADE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uSat,uContrast,uLift,uRolloff;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 c=texture(uTexture,vUV).rgb;
 float inv=1.-uSat;
 float off=uLift+(1.-uContrast)*128.;
 float s=clamp(uRolloff,0.,1.);
 float rScale=1.-s*.18;float rLift=s*14.;
 vec3 cc=c*255.*uContrast+off;
 float lum=dot(cc,vec3(inv*.213,inv*.715,inv*.072));
 vec3 o=(lum+uSat*cc)*rScale+rLift;
 fragColor=vec4(clamp(o,0.,255.)/255.,1.);
}"""


    // ── Pro editor: white balance (temp / tint). Neutral = 0 / 0. ──
    const val FRAG_EDIT_WB = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uTemp,uTint;
in vec2 vUV;out vec4 fragColor;
 void main(){
  vec3 c=texture(uTexture,vUV).rgb;
  float r=c.r*(1.+uTemp*.22+uTint*.12);
  float g=c.g*(1.-uTemp*.05+uTint*.07);
  float b=c.b*(1.-uTemp*.22+uTint*.12);
  vec3 o=vec3(r,g,b);
  float m=max(max(o.r,o.g),o.b);if(m>1.)o/=m;
  fragColor=vec4(clamp(o,0.,1.),1.);
}"""

    // ── Pro editor: HSL mixer — eight hue zones, per-zone hue/sat/lum. ──
    const val FRAG_EDIT_HSL = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform float uH0,uH1,uH2,uH3,uH4,uH5,uH6,uH7;
uniform float uS0,uS1,uS2,uS3,uS4,uS5,uS6,uS7;
uniform float uL0,uL1,uL2,uL3,uL4,uL5,uL6,uL7;
in vec2 vUV;out vec4 fragColor;
vec3 hsv2rgb(vec3 c){
 vec3 p=abs(fract(c.xxx+vec3(0.,2./3.,1./3.))*6.-3.);
 return c.z*mix(vec3(1.),clamp(p-1.,0.,1.),c.y);
}
void main(){
 vec3 c=texture(uTexture,vUV).rgb;
 float mx=max(max(c.r,c.g),c.b),mn=min(min(c.r,c.g),c.b);
 float d=mx-mn,l=(mx+mn)*.5;
 float sat=d/(max(mx+mn,1e-5));
 float h;
 if(d<1e-4)h=0.;else{
  if(mx==c.r)h=(c.g-c.b)/d/6.;
  else if(mx==c.g)h=(c.b-c.r)/d/6.+.3333;
  else h=(c.r-c.g)/d/6.+.6667;
 }
 h=fract(h+1.);
 float dh0=min(abs(h-.0),1.-abs(h-.0)),dh1=min(abs(h-.08333),1.-abs(h-.08333));
 float dh2=min(abs(h-.1667),1.-abs(h-.1667)),dh3=min(abs(h-.3333),1.-abs(h-.3333));
 float dh4=min(abs(h-.5),1.-abs(h-.5)),dh5=min(abs(h-.6667),1.-abs(h-.6667));
 float dh6=min(abs(h-.75),1.-abs(h-.75)),dh7=min(abs(h-.8333),1.-abs(h-.8333));
 float w0=exp(-dh0*dh0*900.),w1=exp(-dh1*dh1*900.);
 float w2=exp(-dh2*dh2*900.),w3=exp(-dh3*dh3*900.);
 float w4=exp(-dh4*dh4*900.),w5=exp(-dh5*dh5*900.);
 float w6=exp(-dh6*dh6*900.),w7=exp(-dh7*dh7*900.);
 float aH=uH0*w0+uH1*w1+uH2*w2+uH3*w3+uH4*w4+uH5*w5+uH6*w6+uH7*w7;
 float aS=uS0*w0+uS1*w1+uS2*w2+uS3*w3+uS4*w4+uS5*w5+uS6*w6+uS7*w7;
 float aL=uL0*w0+uL1*w1+uL2*w2+uL3*w3+uL4*w4+uL5*w5+uL6*w6+uL7*w7;
 float nL=l+aL*.28;
 float nS=sat*(1.+aS);
 float nH=fract(h+aH*.075);
 if(sat<.02) nH=h;
 float v=1.;
 vec3 hsv=vec3(nH,clamp(nS,0.,1.),clamp(nL,0.,1.));
 vec3 o=hsv2rgb(hsv);
 fragColor=vec4(mix(c,o,1.),1.);
}"""

    // ── Pro editor: color grading — shadow / midtone / highlight washes, balance. ──
    const val FRAG_EDIT_SPLIT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform float uShR,uShG,uShB,uMidR,uMidG,uMidB,uHiR,uHiG,uHiB,uBalance,uStrength;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);
 float l=dot(c.rgb,vec3(.2126,.7152,.0722));
 float lr=clamp(l+uBalance*.28,0.,1.);
 float s=pow(1.-clamp(lr*1.4,0.,1.),2.);
 float hh=pow(clamp(lr,0.,1.),2.);
 float md=4.*lr*(1.-lr);
 vec3 t=c.rgb+vec3(uShR,uShG,uShB)*s*.8+vec3(uMidR,uMidG,uMidB)*md*.62+vec3(uHiR,uHiG,uHiB)*hh*.8;
 fragColor=vec4(mix(c.rgb,t,clamp(uStrength,0.,1.)),c.a);
}"""

    // ── Pro editor: structure / local contrast (clarity). ──
    const val FRAG_EDIT_STRUCTURE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uAmount;
in vec2 vUV;out vec4 fragColor;
vec4 S(vec2 uv){return texture(uTexture,clamp(uv,vec2(0.),vec2(1.)));}
void main(){
 vec2 t=1./uResolution;
 vec4 c=S(vUV);
 float lw=dot(c.rgb,vec3(.2126,.7152,.0722));
 vec2 t2=vec2(.0025);
 float lc=dot(c.rgb,vec3(.2126,.7152,.0722));
 float lb=(dot(S(vUV+vec2(-t2.x,-t2.y)).rgb,vec3(.2126,.7152,.0722))
 +dot(S(vUV+vec2(0.,-t2.y)).rgb,vec3(.2126,.7152,.0722))
 +dot(S(vUV+vec2(t2.x,-t2.y)).rgb,vec3(.2126,.7152,.0722))
 +dot(S(vUV+vec2(-t2.x,0.)).rgb,vec3(.2126,.7152,.0722))
 +dot(S(vUV+vec2(t2.x,0.)).rgb,vec3(.2126,.7152,.0722))
 +dot(S(vUV+vec2(-t2.x,t2.y)).rgb,vec3(.2126,.7152,.0722))
 +dot(S(vUV+vec2(0.,t2.y)).rgb,vec3(.2126,.7152,.0722))
 +dot(S(vUV+vec2(t2.x,t2.y)).rgb,vec3(.2126,.7152,.0722)))*.125;
 float g=lc-lb;
 fragColor=vec4(c.rgb*(1.+g*uAmount*2.2),c.a);
}"""

    // ── Pro editor: geometry — straighten (uAngle, radians) + perspective. ──
    const val FRAG_EDIT_GEOM = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uAngle,uPerspV,uPerspH;
in vec2 vUV;out vec4 fragColor;
vec2 mirror(vec2 uv){
 return vec2(uv.x<0.?-uv.x:(uv.x>1.?2.-uv.x:uv.x),uv.y<0.?-uv.y:(uv.y>1.?2.-uv.y:uv.y));
}
void main(){
 vec2 c=vUV-.5;
 float ca=cos(-uAngle),sa=sin(-uAngle);
 vec2 r=vec2(c.x*ca-c.y*sa,c.x*sa+c.y*ca);
 vec2 p=vec2(r.x*(1.+uPerspV*(-r.y*.5)),r.y*(1.+uPerspH*(r.x*.5)));
 vec2 uv=mirror(p+.5);
 fragColor=texture(uTexture,clamp(uv,vec2(0.),vec2(1.)));
}"""

    // ── Pro editor: lens blur (band bokeh, vertical focus falloff). ──
    const val FRAG_EDIT_LENSBLUR = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uFocusY,uFocusW,uFeather,uAmount;
in vec2 vUV;out vec4 fragColor;
vec4 S(vec2 uv){return texture(uTexture,clamp(uv,vec2(0.),vec2(1.)));}
void main(){
 float band=smoothstep(uFocusY-uFocusW*(1.+uFeather)*.5,(uFocusY-uFocusW*.5),vUV.y)
 -smoothstep(uFocusY+uFocusW*.5,uFocusY+uFocusW*(1.+uFeather)*.5,vUV.y);
 float a=uAmount*(1.-band)*.62;
 vec2 t=1./uResolution;
 vec4 sum=S(vUV)*.2;
 sum+=S(vUV+vec2(-t.x,0.))*.16+S(vUV+vec2(t.x,0.))*.16;
 sum+=S(vUV+vec2(0.,-t.y))*.12+S(vUV+vec2(0.,t.y))*.12;
 sum+=S(vUV+vec2(-t.x,-t.y))*.06+S(vUV+vec2(t.x,-t.y))*.06;
 sum+=S(vUV+vec2(-t.x,t.y))*.06+S(vUV+vec2(t.x,t.y))*.06;
 fragColor=mix(S(vUV),sum,a);
}"""

    // Curves with a 256x4 LUT texture: row0 = luma, row1 = R, row2 = G, row3 = B.
    // uChannel: 0=luma, 1..3 = R,G,B. uMix cross-fades with the original.
    // The LUT is bound as uTexture2 (channel row selected inside the shader).
    const val FRAG_EDIT_CURVES = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;uniform float uChannel,uMix;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 c=texture(uTexture,vUV).rgb;
  float l=dot(c,vec3(.2126,.7152,.0722));
  float vo=texture(uTexture2,vec2(l,.125)).a;
  vec3 res;
  if (uChannel<.5) res=c*(vo/max(l,.0001));
  else {
   float r=texture(uTexture2,vec2(c.r,.375)).r;
   float g=texture(uTexture2,vec2(c.g,.625)).g;
   float b=texture(uTexture2,vec2(c.b,.875)).b;
   res=vec3(r,g,b);
  }
  fragColor=vec4(mix(c,res,uMix),1.);
}"""

    // Depth-aware lens bokeh (MiDaS-derived uDepthMap): pixels far from the
    // tapped focus depth blur into shaped (round/polygon) bokeh disks.
    const val FRAG_EDIT_DEPTHBLUR = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uDepthMap;
uniform float uAmount,uFocusDepth,uRange,uBlades;
uniform vec2 uResolution;
in vec2 vUV;out vec4 fragColor;
float polyRad(float ang){
 float n=floor(uBlades+0.5);
 if(n<3.)return 1.;
 float a=mod(ang,6.2831853/n);
 return cos(3.1415926/n)/max(cos(a-3.1415926/n),0.2);
}
void main(){
 vec4 c=texture(uTexture,vUV);
 float d=texture(uDepthMap,vUV).r;
 float bl=clamp(abs(d-uFocusDepth)/max(uRange,0.02),0.,1.)*clamp(uAmount,0.,1.);
 float brad=bl*0.024*uResolution.y*0.5;
 vec4 acc=c;float wsum=1.;
 if(brad>0.5){
  for(int r=1;r<=3;r++){
   float rr=float(r)/3.;
   for(int i=0;i<12;i++){
    float a=(float(i)+0.5)/12.*6.2831853+0.26;
    vec2 dir=vec2(cos(a),sin(a))*polyRad(a);
    vec2 p=vUV+dir*(brad*rr)/uResolution;
    if(p.x<0.||p.y<0.||p.x>1.||p.y>1.)continue;
    acc+=texture(uTexture,p);wsum+=1.;
   }
  }
 }
  fragColor=vec4(acc.rgb/wsum,c.a);
}"""

    // Disc / aperture depth bokeh for still capture — self-contained: it derives the
    // per-pixel circle-of-confusion from the depth map inline (no separate blur-map
    // pass), then performs a golden-angle spiral GATHER. A neighbour contributes to
    // this pixel only if the pixel lies inside the neighbour's CoC disc; weighting
    // by 1/area makes bright lights render as real, shaped bokeh balls. uAperture = 0
    // for a circular kernel, >=3 for an N-gon aperture (triangle…octagon). Falloff is
    // exponential (uExpK) and uForeground toggles blurring objects nearer than focus.
    const val FRAG_EDIT_DISK_BOKEH = """
#version 300 es
precision highp float;
uniform sampler2D uColor;uniform sampler2D uDepthMap;
uniform float uFocusDepth,uRange,uMaxRadius,uAperture,uForeground,uExpK;
uniform vec2 uTexel;uniform int uSamples;
uniform vec2 uDS;uniform vec2 uDO;
in vec2 vUV;out vec4 o;
float polyRadius(float ang,float sides){
  if(sides<2.5)return 1.;
  float seg=6.28318530718/sides;
  float a=mod(ang,seg)-seg*0.5;
  return cos(3.14159265359/sides)/max(cos(a),1e-3);
}
float cocOf(float d){
  float diff=d-uFocusDepth;
  float raw=(uForeground>0.5)?abs(diff):max(diff,0.);
  float n=clamp(raw/uRange,0.,1.);
  return 1.-exp(-uExpK*n);
}
void main(){
  float dC=texture(uDepthMap,vUV*uDS+uDO).r;
  float ownCoc=cocOf(dC);
  vec3 sharp=texture(uColor,vUV).rgb;
  if(ownCoc<0.01){o=vec4(sharp,1.);return;}
  vec3 sum=vec3(0.);float wsum=0.;
  float uN=float(uSamples);
  const int MAX_S=96;
  for(int i=0;i<MAX_S;i++){
    if(i>=uSamples)break;
    float t=(float(i)+0.5)/uN;
    float r=sqrt(t);
    float a=float(i)*2.39996323;
    float shapeR=polyRadius(a,uAperture);
    vec2 off=vec2(cos(a),sin(a))*(r*shapeR*uMaxRadius)*uTexel;
    vec2 uvN=vUV+off;
    float cocN=cocOf(texture(uDepthMap,uvN*uDS+uDO).r);
    float distN=r*shapeR;
    if(cocN>0.02&&distN<=cocN){
      float w=1./max(cocN*cocN,1e-4);
      sum+=texture(uColor,uvN).rgb*w;wsum+=w;
    }
  }
  vec3 blurred=(wsum>0.)?sum/wsum:sharp;
  o=vec4(blurred,1.);
}"""


    // ── FAST separable depth bokeh (editor + capture) ──────────────────────────
    // "Native Android bokeh" speed: the depth-driven circle-of-confusion blur runs
    // at HALF resolution in two separable passes (H then V) and is composited back
    // at full res. Matches the live FusionPipelineEngine.applyCoCBokeh approach.
    // RADIUS-based: each pixel blurs over a window proportional to its own CoC
    // (circle of confusion), so the in-focus band stays pixels-sharp while the
    // background is averaged smoothly. The alpha channel carries the per-pixel
    // CoC for the compositing pass. Loop bounds are constant (GLES3 requirement);
    // the dynamic radius is a per-tap `continue`, which is allowed.
    const val FRAG_DBLUR_COC_H = """
#version 300 es
precision mediump float;
uniform sampler2D uColor;uniform sampler2D uDepth;uniform sampler2D uAlpha;
uniform float uFocusDepth,uRange,uMaxCoC,uTexelW,uEdgeK;
in vec2 vUV;out vec4 fragColor;
float coc(float d){ return clamp(abs(d-uFocusDepth)/max(uRange,0.02f),0.,1.)*clamp(uMaxCoC,0.,1.); }
void main(){
 float d=texture(uDepth,vUV).r;
 float cc=coc(d);
 vec3 c0=texture(uColor,vUV).rgb;
 vec3 sum=c0; float ws=1.0;
 float rad=ceil(cc*22.0);
 for(int i=-22;i<=22;i++){
  if(i==0)continue;
  if(abs(float(i))>rad)continue;
  float x=float(i)*uTexelW;
  vec2 uv=clamp(vUV+vec2(x,0.),vec2(0.),vec2(1.));
  vec3 ct=texture(uColor,uv).rgb;
  // Edge-aware (bilateral) weighting: a tap on the far side of a strong colour
  // edge gets a near-zero weight so background never bleeds onto the subject
  // (and vice-versa) — no hard cutouts, no halo.
  float cd=length(ct-c0);
  float w=1.0/(1.0+uEdgeK*cd);
  sum+=ct*w; ws+=w;
 }
 vec3 blurred=sum/ws;
 vec4 o=vec4(blurred,cc);
 fragColor=o;
}"""

    const val FRAG_DBLUR_COC_V = """
#version 300 es
precision mediump float;
uniform sampler2D uColor;uniform sampler2D uDepth;uniform sampler2D uAlpha;
uniform float uFocusDepth,uRange,uMaxCoC,uTexelH,uEdgeK;
in vec2 vUV;out vec4 fragColor;
float coc(float d){ return clamp(abs(d-uFocusDepth)/max(uRange,0.02f),0.,1.)*clamp(uMaxCoC,0.,1.); }
void main(){
 float d=texture(uDepth,vUV).r;
 float cc=coc(d);
 vec3 c0=texture(uColor,vUV).rgb;
 vec3 sum=c0; float ws=1.0;
 float rad=ceil(cc*22.0);
 for(int i=-22;i<=22;i++){
  if(i==0)continue;
  if(abs(float(i))>rad)continue;
  float y=float(i)*uTexelH;
  vec2 uv=clamp(vUV+vec2(0.,y),vec2(0.),vec2(1.));
  vec3 ct=texture(uColor,uv).rgb;
  float cd=length(ct-c0);
  float w=1.0/(1.0+uEdgeK*cd);
  sum+=ct*w; ws+=w;
 }
 vec3 blurred=sum/ws;
 vec4 o=vec4(blurred,cc);
 fragColor=o;
}"""

    const val FRAG_DBLUR_COMP = """
#version 300 es
precision mediump float;
uniform sampler2D uColor;uniform sampler2D uBlur;uniform sampler2D uAlpha;
uniform float uMaxCoC,uSegMix;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 s=texture(uColor,vUV);
 vec4 b=texture(uBlur,vUV);
 float cc=clamp(b.a/max(uMaxCoC,1e-3),0.,1.);
 vec3 o=mix(s.rgb,b.rgb,cc);
 float seg=texture(uAlpha,vUV).r;
 o=mix(o,s.rgb,mix(0.,1.-cc,seg*uSegMix));
 fragColor=vec4(o,s.a);
}"""

    // ── Pro editor: masked local adjustments (Lightroom-style). A CPU-painted mask
//    bitmap sits in uTexture2 (alpha channel = mask). Exposure / contrast /
//    saturation / warmth apply inside the mask with a strength mix; optional
//    red-tint visualization. ──
    const val FRAG_EDIT_MASK = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;
uniform float uBrightness,uSaturation,uWarmth,uContrast,uExposure,uStrength,uInverted,uShow;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);
 float m=texture(uTexture2,vUV).a;
 if(uInverted>.5)m=1.-m;
 vec3 g=c.rgb*pow(2.,uExposure*1.2);
 g=(g-.5)*(1.+uContrast)+.5;
 float gl=dot(g,vec3(.2126,.7152,.0722));
 g=mix(vec3(gl),g,clamp(1.+uSaturation,0.,3.));
 g=g*max(1.+uBrightness*.45,.05);
 float r=g.r*(1.+uWarmth*.2);
 float b=g.b*(1.-uWarmth*.2);
 float mx=max(max(r,g.g),b);if(mx>1.){r/=mx;g/=mx;b/=mx;}
 vec3 adj=vec3(r,g.g,b);
 vec3 o=mix(c.rgb,adj,m*clamp(uStrength,0.,1.5));
 vec3 vis=vec3(1.,.2,.15);
 o=mix(o,vis,m*.32*uShow);
 fragColor=vec4(o,c.a);
}"""

    // Selective: up to 6 circular adjustment spots — brightness / saturation /
    // warmth inside a gaussian falloff. Spots are uniform vec4s (x,y,r,weight)
    // plus a vec2 per point (sat, warmth).
    const val FRAG_EDIT_SELECT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uCount;
uniform vec4 uSpots[6];uniform vec3 uAdj[6];
in vec2 vUV;out vec4 fragColor;
float lum(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
void main(){
 vec3 c=texture(uTexture,vUV).rgb;
 float n=floor(uCount*6.+.5);
 for(int i=0;i<6;i++){
  if(float(i)>=n)break;
  vec2 d=(vUV-uSpots[i].xy)/max(uSpots[i].z,1e-3);
  float w=exp(-dot(d,d)*1.7)*uSpots[i].w;
  if(w<.01)continue;
  c+=c*(uAdj[i].x*.45)*w;
  float l=lum(c);
  c=mix(vec3(l),c,clamp(1.+(uAdj[i].y-1.)*w,0.,3.));
  l=lum(c);
  c+=c*(uAdj[i].z*.3)*w;
 }
 fragColor=vec4(c,1.);
}"""

    // ── Pro editor: heal — spot repair. Each spot samples the ring of pixels
    //    just OUTSIDE its radius (8 taps at r*1.6 around the center) and blends
    //    that sampled average into the interior, feathered by uSpots[].w — a
    //    simple inference-free clone/repair that removes dust, sensor spots and
    //    small defects without any learning model. Neutral when no spots. ──
    const val FRAG_EDIT_HEAL = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uCount;
uniform vec4 uSpots[6];
in vec2 vUV;out vec4 fragColor;
vec3 S(vec2 uv){return texture(uTexture,clamp(uv,vec2(0.),vec2(1.))).rgb;}
void main(){
 vec3 c=S(vUV);
 float n=floor(uCount*6.+.5);
 for(int i=0;i<6;i++){
  if(float(i)>=n)break;
  vec2 d=vUV-uSpots[i].xy;
  float r=max(uSpots[i].z,1e-3);
  float dist=length(d);
  float we=1.-smoothstep(r*.35,r,dist);
  if(we<.01)continue;
  vec3 avg=vec3(0.);
  for(int k=0;k<8;k++){
   float ang=float(k)*6.2831853/8.;
   vec2 p=uSpots[i].xy+vec2(cos(ang),sin(ang))*r*1.6;
   avg+=S(p);
  }
  avg*=0.125;
  float soft=mix(.35,.85,uSpots[i].w);
  float w2=1.-smoothstep(r*soft,r,dist);
  c=mix(c,avg,w2);
 }
 fragColor=vec4(c,1.);
}"""

    // ── Pro editor: detail — sharpening (amount / radius / masking / detail) and
    //    luma + color noise reduction in one pass. All sliders neutral at 0. ──
    const val FRAG_EDIT_DETAIL = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uAmount,uRadius,uDetail,uMasking,uLumNR,uColorNR;
in vec2 vUV;out vec4 fragColor;
float lum(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
vec4 S(vec2 uv){return texture(uTexture,clamp(uv,vec2(0.),vec2(1.)));}
void main(){
 vec2 t=1./uResolution;
 vec3 c=S(vUV).rgb;
 vec2 kb=vec2(1.)+uRadius*3.5;
 vec2 tb=t*kb;
 vec3 b=(S(vUV+vec2(-tb.x,0.)).rgb+S(vUV+vec2(tb.x,0.)).rgb
      +S(vUV+vec2(0.,-tb.y)).rgb+S(vUV+vec2(0.,tb.y)).rgb)*.25;
 vec3 b2=(c+S(vUV+vec2(-t.x,0.)).rgb+S(vUV+vec2(t.x,0.)).rgb
       +S(vUV+vec2(0.,-t.y)).rgb+S(vUV+vec2(0.,t.y)).rgb)*.2;
 float lca=lum(c),lcb=lum(b2);
 float edge=abs(lca-lcb);
 float gate=clamp(1.25-edge/max(uMasking+.08,.081),0.,1.);
 gate=1.-pow(1.-gate,1.5);
 float smallAmt=mix(.45,1.,clamp(uDetail,0.,1.));
 vec3 sh=c+(c-b2)*uAmount*.9*smallAmt*gate;
 sh+=c+(c-b)*(uAmount*.4*(1.-smallAmt))*gate;
 float nrL=clamp(1.-edge/max(uLumNR+.06,.061),0.,1.);
 vec3 o=mix(sh,sh*(lcb/max(lca,1e-4)),nrL*uLumNR*.85);
 float nrC=clamp(1.-edge/max(uColorNR+.06,.061),0.,1.);
 o=mix(o,vec3(lum(o)),nrC*uColorNR*.65);
 fragColor=vec4(o,1.);
}"""

    // ── Pro editor: effects — texture (micro local contrast), clarity (midtone
    //    local contrast), dehaze (atmosphere lift reduction). Neutral at 0. ──
    const val FRAG_EDIT_EFFECTS = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uText,uClarity,uDehaze;
in vec2 vUV;out vec4 fragColor;
float lum(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
vec4 S(vec2 uv){return texture(uTexture,clamp(uv,vec2(0.),vec2(1.)));}
void main(){
 vec3 c=S(vUV).rgb;float l=lum(c);
 vec2 t=1./uResolution;
 vec3 b1=(S(vUV+vec2(-t.x,0.)).rgb+S(vUV+vec2(t.x,0.)).rgb
       +S(vUV+vec2(0.,-t.y)).rgb+S(vUV+vec2(0.,t.y)).rgb)*.25;
 vec2 t2=vec2(.004);
 vec3 b2=(S(vUV+vec2(-t2.x,-t2.y)).rgb+S(vUV+vec2(0.,-t2.y)).rgb+S(vUV+vec2(t2.x,-t2.y)).rgb
       +S(vUV+vec2(-t2.x,0.)).rgb+S(vUV+vec2(t2.x,0.)).rgb
       +S(vUV+vec2(-t2.x,t2.y)).rgb+S(vUV+vec2(0.,t2.y)).rgb+S(vUV+vec2(t2.x,t2.y)).rgb)*.125;
 vec3 o=c;
 o=o*(1.+(lum(b1)-l)*uText*1.6);
 o=o*(1.+(lum(b2)-l)*uClarity*2.4*(4.*l*(1.-l)));
 float hz=smoothstep(.18,.85,l);
 o=o*(1.+uDehaze*.55-hz*uDehaze*.46);
 float ld=lum(o);
 ld=(ld-.5)*(1.+uDehaze*.6)+.5;
 o=mix(vec3(ld),o,clamp(1.-uDehaze*.62,0.,1.));
 fragColor=vec4(clamp(o,0.,1.),1.);
}"""

    // ── Pro editor: optics — lens distortion (barrel/pincushion) + chromatic
    //    aberration correction. Neutral at 0. ──
    const val FRAG_EDIT_OPTICS = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uDistortion,uCA;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 c=vUV-.5;
 float r2=dot(c,c);
 float k=1.+uDistortion*r2*2.2;
 vec2 uv=(c*k)+.5;
 float rad=dot(uv-.5,uv-.5)*2.+1.;
 float rr=1.+uCA*.012*rad;
 float rb=1.-uCA*.012*rad;
 float r=texture(uTexture,clamp(uv*rr,vec2(0.),vec2(1.))).r;
 float g=texture(uTexture,clamp(uv,vec2(0.),vec2(1.))).g;
 float b=texture(uTexture,clamp(uv*rb,vec2(0.),vec2(1.))).b;
 fragColor=vec4(r,g,b,1.);
}"""

    // ── Pro editor: markup — overlay ink bitmap in uTexture2 composited by its
    //    alpha (uMix 0..1 fades the whole stroke layer). ──
    const val FRAG_MARKUP = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform sampler2D uTexture2;uniform float uMix;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 base=texture(uTexture,vUV);
 vec4 ink=texture(uTexture2,vUV);
 vec3 o=mix(base.rgb,ink.rgb,ink.a*uMix);
 fragColor=vec4(o,1.);
}"""

    const val FRAG_STARBURST = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uIntensity,uTime,uOriginX,uOriginY;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec2 uv=vUV*2.-1.;uv.x*=uResolution.x/uResolution.y;
 vec2 o=vec2(uOriginX*2.-1.,uOriginY*2.-1.);o.x*=uResolution.x/uResolution.y;
 vec2 d=uv-o;float r=length(d),a=atan(d.y,d.x);
 float s=8.,sa=a+uTime*.05;float st=pow(1.-abs(sin(sa*s*.5)),8.)*exp(-r*2.)*(1.-exp(-r*6.));
 float sr=pow(1.-abs(sin((a+uTime*.03)*s)),12.)*.3;
 float hs=exp(-abs(uv.y-o.y)*30.)*exp(-abs(uv.x-o.x)*.5)*.15;
 float fl=(st+sr+hs)*uIntensity;vec3 fc=mix(vec3(1.),vec3(1.,.87,.53),.7);
 fragColor=vec4(c.rgb+fc*fl,c.a);
}"""

    const val FRAG_GLITCH = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uTime,uIntensity;
in vec2 vUV;out vec4 fragColor;
float h(float n){return fract(sin(n)*43758.5453);}
void main(){
 float b=floor(vUV.y*40.+uTime*5.);float j=(h(b)-.5)*.05*uIntensity*step(.85,h(b*1.37));
 vec2 uj=vec2(vUV.x+j,vUV.y);float r=texture(uTexture,vec2(uj.x+.01*uIntensity,uj.y)).r;
 vec4 g=texture(uTexture,uj);float bl=texture(uTexture,vec2(uj.x-.01*uIntensity,uj.y)).b;
 fragColor=vec4(r,g.g,bl,1.);
}"""

    const val FRAG_LIGHT_LEAK = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uTime;uniform vec2 uEntryPoint;uniform vec3 uLeakColor;
in vec2 vUV;out vec4 fragColor;
void main(){
 float p=.85+.15*sin(uTime*.6);float l=pow(1.-smoothstep(0.,.9*p,distance(vUV,uEntryPoint)),2.2);
 vec4 c=texture(uTexture,vUV);vec3 s=1.-(1.-c.rgb)*(1.-uLeakColor*l);fragColor=vec4(s,c.a);
}"""

    const val FRAG_DOUBLE_EXP = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform float uMix;
in vec2 vUV;out vec4 fragColor;
vec3 sB(vec3 a,vec3 b){return 1.-(1.-a)*(1.-b);}
void main(){
 vec4 a=texture(uTexture,vUV);vec4 b=texture(uTexture2,vUV);
 fragColor=vec4(sB(a.rgb,b.rgb*uMix),1.);
}"""

    const val FRAG_BW_GRAIN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec3 uWeights;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);fragColor=vec4(vec3(dot(c.rgb,uWeights)),c.a);
}"""

    const val FRAG_LUT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform float uLutN,uIntensity;
in vec2 vUV;out vec4 fragColor;
vec3 sampleLut(vec3 c){
 float s=uLutN-1.;float bI=c.b*s;float z0=floor(bI);float z1=min(z0+1.,s);float zf=fract(bI);
 vec2 px=vec2(.5)+c.rg*s;vec3 s0=texture(uTexture2,vec2((px.x+z0*uLutN)/(uLutN*uLutN),px.y/uLutN)).rgb;
 vec3 s1=texture(uTexture2,vec2((px.x+z1*uLutN)/(uLutN*uLutN),px.y/uLutN)).rgb;return mix(s0,s1,zf);
}
void main(){vec4 src=texture(uTexture,vUV);vec3 g=sampleLut(clamp(src.rgb,0.,1.));fragColor=vec4(mix(src.rgb,g,uIntensity),src.a);}"""

    const val FRAG_WIDE_ANGLE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uStrength;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 c=vUV*2.-1.;c.x*=uResolution.x/uResolution.y;float r=length(c);
 float t=atan(c.y,c.x);float rD=pow(r,1./(1.+uStrength));
 vec2 d=vec2(cos(t),sin(t))*rD;d.x/=uResolution.x/uResolution.y;vec2 uv2=d*.5+.5;
 if(uv2.x<0.||uv2.x>1.||uv2.y<0.||uv2.y>1.)fragColor=vec4(0.);else fragColor=texture(uTexture,uv2);
}"""

    // Phase 3 — Depth-driven (require uDepthMap uniform from depth buffer)
    const val FRAG_DEPTH_HALATION = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2,uDepthMap;uniform vec3 uTint;uniform float uFocusPlane,uDepthBand,uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 s=texture(uTexture,vUV);vec4 h=texture(uTexture2,vUV);
 float d=texture(uDepthMap,vUV).r;float m=1.-smoothstep(0.,uDepthBand,abs(d-uFocusPlane));
 fragColor=vec4(s.rgb+h.rgb*uTint*m*uIntensity,s.a);
}"""
    const val FRAG_DEPTH_TILT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2,uDepthMap;uniform float uFocusDepth,uDepthFeather;
in vec2 vUV;out vec4 fragColor;
void main(){
 float d=texture(uDepthMap,vUV).r;float b=smoothstep(0.,uDepthFeather,abs(d-uFocusDepth));
 vec4 s=texture(uTexture,vUV);vec4 bl=texture(uTexture2,vUV);fragColor=mix(s,bl,b);
}"""
    const val FRAG_DEPTH_CA = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uDepthMap;uniform vec2 uResolution;uniform float uFocusDepth,uMaxStrength;
in vec2 vUV;out vec4 fragColor;
void main(){
 float d=texture(uDepthMap,vUV).r;float s=abs(d-uFocusDepth)*uMaxStrength;
 vec2 dir=(vUV-.5)*s;float r=texture(uTexture,vUV+dir).r;vec4 g=texture(uTexture,vUV);float b=texture(uTexture,vUV-dir).b;
 fragColor=vec4(r,g.g,b,1.);
}"""
    const val FRAG_DEPTH_DOUBLE = """
#version 300 es
precision mediump float;
uniform sampler2D uFrameA,uFrameB,uDepthA;uniform float uForegroundDepth,uMix;
in vec2 vUV;out vec4 fragColor;
vec3 sB(vec3 a,vec3 b){return 1.-(1.-a)*(1.-b);}
void main(){
 vec4 a=texture(uFrameA,vUV);vec4 b=texture(uFrameB,vUV);
 float d=texture(uDepthA,vUV).r;float fg=1.-smoothstep(uForegroundDepth-.05,uForegroundDepth+.05,d);
 fragColor=vec4(mix(sB(a.rgb,b.rgb*uMix),a.rgb,fg),1.);
}"""

    // Phase 4 — Sensor-driven (motion energy, ISO, gyro)
    const val FRAG_MOTION_BLUR = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uMotionIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 float s=uMotionIntensity*.005;vec3 c=vec3(0.);
 for(int i=-3;i<=3;i++){float f=float(i)/3.;c+=texture(uTexture,vUV+vec2(f*s,0.)).rgb*(1.-abs(f)*.3);}
 fragColor=vec4(c/7.,1.);
}"""
    const val FRAG_ISO_GRAIN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uIsoLevel,uIntensity;
in vec2 vUV;out vec4 fragColor;
float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
void main(){
 vec4 c=texture(uTexture,vUV);float g=(h(vUV*uResolution+vec2(0.,uIsoLevel*100.))-.5)*uIsoLevel*.004*uIntensity;
 fragColor=vec4(c.rgb+g,c.a);
}"""
    const val FRAG_GYRO_LEAK = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uTime;uniform vec2 uGyroDir;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 float d=dot(vUV-.5,normalize(uGyroDir));float l=smoothstep(.3,.8,abs(d))*uIntensity;
 vec3 leak=vec3(1.,.6,.3)*l*.3;vec4 c=texture(uTexture,vUV);
 fragColor=vec4(c.rgb+leak,c.a);
}"""

    // Standalone Phase 1 additions
    const val FRAG_SOFT_FOCUS = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 o=vec2(1.5)/uResolution;vec3 s=vec3(0.);
 s+=texture(uTexture,vUV+vec2(-o.x,0.)).rgb*.15;s+=texture(uTexture,vUV+vec2(o.x,0.)).rgb*.15;
 s+=texture(uTexture,vUV+vec2(0.,-o.y)).rgb*.15;s+=texture(uTexture,vUV+vec2(0.,o.y)).rgb*.15;
 s+=texture(uTexture,vUV).rgb*.4;s+=texture(uTexture,vUV+vec2(-o.x,-o.y)).rgb*.05;
 s+=texture(uTexture,vUV+vec2(o.x,-o.y)).rgb*.05;s+=texture(uTexture,vUV+vec2(-o.x,o.y)).rgb*.05;
 s+=texture(uTexture,vUV+vec2(o.x,o.y)).rgb*.05;
 vec4 c=texture(uTexture,vUV);fragColor=vec4(mix(c.rgb,s,uIntensity),c.a);
}"""
    const val FRAG_COLOR_SHIFT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 s=c.rgb;
 s.r=pow(s.r,1.-uIntensity*.15);s.g=pow(s.g,1.+uIntensity*.05);s.b=pow(s.b,1.+uIntensity*.1);
 float l=dot(c.rgb,vec3(.299,.587,.114));fragColor=vec4(mix(s,mix(c.rgb,vec3(l),.3),uIntensity*.5),c.a);
}"""

    // Defocus-based depth — blur difference gives scene depth
    const val FRAG_DEPTH_GEN = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture,uTexture2;uniform float uFocusDepth,uDepthBand;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec3 s=texture(uTexture,vUV).rgb;vec3 b=texture(uTexture2,vUV).rgb;
 float diff=dot(abs(s-b),vec3(.299,.587,.114));
 float depth=1.-smoothstep(0.,.15,diff);
 fragColor=vec4(vec3(depth),1.);
}"""

    // instant — high contrast, blue-tinted, soft glow
    const val FRAG_INSTANT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 s=c.rgb;
 s=pow(s,vec3(.85,.9,.95));s*=vec3(.95,.98,1.05);
 s=mix(s,s+vec3(.02,.06,.1)*.15,uIntensity);fragColor=vec4(mix(c.rgb,s,uIntensity),c.a);
}"""
    const val FRAG_RETRO = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;uniform float uTime;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 s=c.rgb;
 s=pow(s,vec3(.8,.85,.9));s*=vec3(1.1,.95,.85);
 s=mix(s,vec3(dot(s,vec3(.299,.587,.114)))*1.05,uIntensity*.3);
 s=mix(c.rgb,s,uIntensity);fragColor=vec4(s,c.a);
}"""

    // Luminance Reduction 4x4 — for GPU-side adaptive light-leak direction
    const val FRAG_LUMA_4X4 = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uCellSize;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 o=vUV*uCellSize;vec3 s=vec3(0.);
 for(int y=0;y<4;y++)for(int x=0;x<4;x++)s+=texture(uTexture,o+(vec2(x,y)+.5)*(uCellSize/4.)).rgb;
 float l=dot(s/16.,vec3(.2126,.7152,.0722));fragColor=vec4(vec3(l),1.);
}"""

    // Scene average 16x16 — RGB kept so the renderer can extract the brightest
    // region (light-leak entry / lens-flare source) AND its dominant color.
    const val FRAG_SCENE_AVG = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uCellSize;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 o=vUV*uCellSize;vec2 s2=uCellSize/4.;vec3 s=vec3(0.);
 for(int y=0;y<4;y++)for(int x=0;x<4;x++)s+=texture(uTexture,o+(vec2(x,y)+.5)*s2).rgb;
 fragColor=vec4(s/16.,1.);
}"""

    // Film composite (leak + grain + vignette + tint + warmth + fade +
    // mono) mirroring the CPU FilmOverlay so the live view == captured output.
    const val FRAG_FILM_STYLE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uTime,uFrame;
uniform float uGrain,uVignette,uFade,uWarmth,uMono,uTintAlpha,uLeak,uAdaptive;
uniform float uFSat,uFContrast,uFLift,uFRolloff;
uniform vec2 uEntryPoint;
uniform vec3 uTint,uLeakColor;
in vec2 vUV;out vec4 fragColor;
float hh(vec2 p){p=fract(p*vec2(443.9,397.3));p+=dot(p,p+19.2);return fract(p.x*p.y);}
void main(){
 vec4 c=texture(uTexture,vUV);vec3 col=c.rgb;
 if(uTintAlpha>.001)col=mix(col,col*uTint*1.18,uTintAlpha*.55);
 if(abs(uWarmth)>.001){vec3 wc=uWarmth>.5?vec3(1.,.53,.24):vec3(.0,.2,1.);col=mix(col,wc,abs(uWarmth)*.09);}
 if(uMono>.5)col=vec3(dot(col,vec3(.299,.587,.114)));
 if(uFSat!=1.||uFContrast!=1.||uFLift>.001||uFRolloff>.001){
  float off=uFLift+(1.-uFContrast)*.5;
  vec3 gc=col*uFContrast+off;
  float inv=1.-clamp(uFSat,0.,4.);
  float lum=dot(gc,vec3(inv*.213,inv*.715,inv*.072));
  gc=vec3(lum)+clamp(uFSat,0.,4.)*gc;
  float rs=1.-clamp(uFRolloff,0.,1.)*.18;
  gc=gc*rs+clamp(uFRolloff,0.,1.)*14./255.;
  col=clamp(gc,0.,1.);
 }
 if(uFade>.001)col=mix(col,vec3(1.),uFade*.13);
 float vig=1.-smoothstep(.42,.95,length((vUV-.5)*vec2(uResolution.x/uResolution.y,1.)));
 col=mix(col,col*vig,uVignette);
 if(uLeak>.001){
  vec2 ep=uAdaptive>.5?uEntryPoint:vec2(.18,.12);
  float dist=distance(vUV,ep);
  float p=.8+.2*sin(uTime*.5);
  float body=pow(1.-smoothstep(0.,.16*p,dist),2.4);
  float core=exp(-dist*9.)*.5;
  col=1.-(1.-col)*(1.-uLeakColor*(body*1.6+core)*uLeak);
 }
 col+=vec3(hh(vUV*uResolution+vec2(uFrame*17.,0.))-.5)*uGrain*.16;
 fragColor=vec4(clamp(col,0.,1.),c.a);
}"""

    // Realistic lens flare: adaptive bright-spot source + its color, uncorrected
    // ghost chain, anamorphic double streak, iris ring halo and WB shift.
    const val FRAG_LENS_FLARE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uSourcePos;uniform vec3 uSourceColor;
uniform float uIntensity,uAnamorphic,uTime;
in vec2 vUV;out vec4 fragColor;
float gst(vec2 p,vec2 uv){return exp(-dot(uv-p,uv-p)*50.);}
void main(){
 vec4 c=texture(uTexture,vUV);vec3 o=c.rgb;
 vec2 sp=clamp(uSourcePos,.002,.998);
 vec3 sr=texture(uTexture,sp).rgb;
 float lm=dot(sr,vec3(.2126,.7152,.0722));
 float a=smoothstep(.10,.75,lm)*uIntensity;
 if(a>.004){
  vec2 cn=vec2(.5);vec2 del=cn-sp;float dl=max(length(del),.0001);vec2 dirx=del/dl;
  float wf=clamp((dot(sr,vec3(1.,.55,.2))/(dot(sr,vec3(.15,.5,1.))+.001)-.5)*2.,0.,1.);
  vec3 f=vec3(0.);
  for(int i=0;i<7;i++){
   float fi=float(i);
   vec2 pc=vec2(.5)-dirx*(0.02+dl*0.12+dl*fi*0.11);
   float sz=.035/(1.+fi*0.18);
   float ca=.0015*(1.+fi);
   vec2 pcu=pc+dirx*ca;
   float r=gst(pcu,vUV);
   float gg=gst(pc,vUV);
   vec2 pcd=pc-dirx*ca;
   float bb=gst(pcd,vUV);
   vec3 gc=mix(vec3(.45,.6,1.),vec3(1.,.7,.3),mod(fi,2.));
   f+=vec3(r,gg,bb)*mix(gc,vec3(1.,.85,.6),wf*.5)*.8*a;
  }
  vec2 dv=vUV-sp;
  float sx=exp(-abs(dv.x)*12.)*exp(-abs(dv.y)*240.);
  float sy=exp(-abs(dv.y)*12.)*exp(-abs(dv.x)*240.)*uAnamorphic;
  f+=uSourceColor*1.6*(sx*.16+sy*.10)*a;
  float dd=length(vUV-sp);
  float ring=exp(-pow((dd*.06-1.)*260.,2.));
  f+=vec3(1.,.96,.9)*ring*.16*a;
  f+=uSourceColor*exp(-dd*dd*36.)*.35*a;
  f+=uSourceColor*exp(-dot(vUV-vec2(.5),vUV-vec2(.5))*14.)*.04*a;
  o=o*(1.+a*.10);
  o=mix(o,o*uSourceColor*1.5+.4,a*.08);
  o=mix(o,vec3(dot(o,vec3(.299,.587,.114))),a*.05);
  o+=f;
 }
 fragColor=vec4(clamp(o,0.,1.),c.a);
}"""

    // Volumetric god rays from the detected bright spot. Grid-away the old fake
    // rotating rasp: each pixel marches 14 steps toward the source, integrating
    // the scene's luminance field with decay. Where the path crosses bright sky
    // the beam glows; tree/cloud occluders carve dark lanes through it — the
    // real god-ray look, no animation tricks (uTime kept for the frame-rate-
    // independent dither below, static ray field otherwise).
    const val FRAG_LIGHT_RAYS = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform vec2 uSourcePos;uniform vec3 uSourceColor;
uniform float uIntensity,uTime;
in vec2 vUV;out vec4 fragColor;
float lum(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
void main(){
 vec4 src=texture(uTexture,vUV);
 vec2 sp=clamp(uSourcePos,.001,.999);
 float aR=uResolution.x/max(uResolution.y,1.);
 vec2 uvA=vec2(vUV.x*aR,vUV.y), spA=vec2(sp.x*aR,sp.y);
 float dist=length(uvA-spA);
 vec3 acc=vec3(0.);
 float w=1.;
 const int N=14;
 for(int i=1;i<=N;i++){
  float t=float(i)/float(N);
  vec2 p=mix(vUV,sp,t);
  acc+=vec3(lum(texture(uTexture,clamp(p,vec2(0.),vec2(1.))).rgb))*w;
  w*=.93;
 }
 float att=exp(-dist*1.6)+exp(-dist*dist)*.9;
 vec3 rays=acc*(uIntensity*2.6)*att;
 rays=min(rays,vec3(3.));
 fragColor=vec4(src.rgb+rays*uSourceColor,1.);
}"""

    // Split toning: independent shadow + highlight color washes.
    const val FRAG_SPLIT_TONE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec3 uShadowTint,uHighlightTint;
uniform float uIntensity,uBalance;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);
 float l=dot(c.rgb,vec3(.2126,.7152,.0722));
 float s=pow(1.-clamp(l*1.4-uBalance,.0,1.),2.);
 float h=pow(l-uBalance,2.);
 vec3 t=c.rgb+uShadowTint*s*.8+uHighlightTint*h*.8;
 fragColor=vec4(mix(c.rgb,t,uIntensity),c.a);
}"""

    // Faded print film: lifted blacks, pulled highlights, warm paper cast.
    const val FRAG_FADED_FILM = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);
 vec3 r=mix(c.rgb,pow(max(c.rgb,0.),vec3(.88)),.65);
 r=mix(r,vec3(.985,.96,.885),.1);
 r=mix(r,vec3(dot(c.rgb,vec3(.299,.587,.114))),uIntensity*.05);
 vec3 f=mix(c.rgb,r,uIntensity);
 fragColor=vec4(f,c.a);
}"""

    val FRAG_BLUR_H get() = SeparableBlur.FRAG_BLUR_H
    val FRAG_BLUR_V get() = SeparableBlur.FRAG_BLUR_V

    val ALL_PHASE_3 = mapOf(
        "depth_halation" to FRAG_DEPTH_HALATION,
        "depth_tilt" to FRAG_DEPTH_TILT,
        "depth_ca" to FRAG_DEPTH_CA,
        "depth_double" to FRAG_DEPTH_DOUBLE
    )

    val ALL_PHASE_4 = mapOf(
        "motion_blur" to FRAG_MOTION_BLUR,
        "iso_grain" to FRAG_ISO_GRAIN,
        "gyro_leak" to FRAG_GYRO_LEAK
    )

    const val FRAG_FRAME_OVERLAY = """
#version 300 es
precision mediump float;
uniform sampler2D uImage;
uniform sampler2D uFrame;
uniform float uFrameIntensity;
in vec2 vUV;
out vec4 fragColor;
void main() {
    vec3 img = texture(uImage, vUV).rgb;
    // Frame bitmaps are uploaded with bitmap-row-0 at texture v=0 (GL bottom),
    // while the camera image is already orientation-normalised by its STMatrix,
    // so the frame must be sampled Y-flipped to line up with the image.
    vec4 frame = texture(uFrame, vec2(vUV.x, 1.0 - vUV.y));
    vec3 col = mix(img, frame.rgb, frame.a * uFrameIntensity);
    fragColor = vec4(col, 1.0);
}"""

    const val FRAG_FOCUS_PEAK = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 px=1./uResolution;vec4 c=texture(uTexture,vUV);
 float l=dot(c.rgb,vec3(.299,.587,.114));
 float gx=dot(texture(uTexture,vUV+vec2(px.x,0.)).rgb,vec3(.299,.587,.114))-l;
 float gy=dot(texture(uTexture,vUV+vec2(0.,px.y)).rgb,vec3(.299,.587,.114))-l;
 float e=length(vec2(gx,gy));
 float peak=smoothstep(.05,.25,e)*uIntensity;
 vec3 highlight=mix(c.rgb,vec3(1.,.2,.1),peak*.8);
 fragColor=vec4(mix(highlight,c.rgb,1.-peak*.2),1.);
}"""

    const val FRAG_FALSE_COLOR = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);
 float l=dot(c.rgb,vec3(.299,.587,.114));
 vec3 fc;if(l<.15)fc=vec3(0.,0.,.78);else if(l<.3)fc=vec3(0.,.4,1.);else if(l<.45)fc=vec3(0.,.78,.4);else if(l<.55)fc=vec3(0.,1.,0.);else if(l<.7)fc=vec3(1.,.78,0.);else if(l<.85)fc=vec3(1.,.4,0.);else fc=vec3(1.,0.,0.);
 fragColor=vec4(mix(c.rgb,fc,uIntensity*.5),1.);
}"""

    const val FRAG_CRT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;
uniform float uIntensity;
uniform float uResolutionX;
uniform float uResolutionY;
uniform float uTime;
in vec2 vUV;
out vec4 fragColor;

void main() {
    vec2 uv = vUV;
    float i = uIntensity;
    // Screen curvature
    vec2 c = uv - 0.5;
    float k = i * 0.12;
    uv = uv + c * c * c * k;
    // Edge clamp
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { fragColor = vec4(0.0); return; }
    // Chromatic aberration at edges
    float edge = length(vUV - 0.5) * 0.7;
    float ca = i * 0.015 * edge;
    float r = texture(uTexture, uv + vec2(ca, 0.0)).r;
    float g = texture(uTexture, uv).g;
    float b = texture(uTexture, uv - vec2(ca, 0.0)).b;
    vec3 col = vec3(r, g, b);
    // Scanlines
    float sc = sin(uv.y * uResolutionY * 3.14159);
    float scan = 1.0 - (1.0 - sc * 0.5) * i * 0.55;
    col *= scan;
    // Sub-pixel phosphor dots
    float px = sin(uv.x * uResolutionX * 3.14159 * 0.5) * 0.5 + 0.5;
    float ph = sin(uv.y * uResolutionY * 3.14159 * 1.5 + 1.0) * 0.5 + 0.5;
    float dot = px * ph;
    col.r *= 1.0 - (1.0 - dot) * i * 0.12;
    // Phosphor glow
    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col += pow(luma, 2.0) * vec3(0.12, 0.06, 0.22) * i * 0.6;
    // Flicker
    float fl = 1.0 + sin(uTime * 50.0 + uv.y * 100.0) * 0.01 * i;
    col *= fl;
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}"""

    // ─── New Effects ───
    const val FRAG_IN_COLOR = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity,uWarmth;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec4 c=texture(uTexture,vUV);vec3 yiq;
 yiq.r=dot(c.rgb,vec3(.299,.587,.114));
 yiq.g=dot(c.rgb,vec3(.596,-.274,-.322))*(1.+.6*uIntensity);
 yiq.b=dot(c.rgb,vec3(.211,-.523,.312))*(1.+.6*uIntensity);
 float a=uWarmth*.15,ca=cos(a),sa=sin(a);
 vec2 rt=vec2(yiq.g*ca-yiq.b*sa,yiq.g*sa+yiq.b*ca);yiq.gb=rt;
 vec3 rgb=vec3(yiq.r+.956*yiq.g+.619*yiq.b,yiq.r-.272*yiq.g-.647*yiq.b,yiq.r-1.106*yiq.g+1.703*yiq.b);
 rgb=pow(rgb,vec3(.9))*1.05;
 fragColor=vec4(mix(c.rgb,clamp(rgb,0.,1.),uIntensity),c.a);
}"""
    const val FRAG_1BIT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform float uIntensity,uScale;
in vec2 vUV;out vec4 fragColor;
const int b4[16]=int[](0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5);
void main(){
 vec4 c=texture(uTexture,vUV);
 float lm=dot(c.rgb,vec3(.2126,.7152,.0722));
 int x=int(mod(gl_FragCoord.x/uScale,4.)),y=int(mod(gl_FragCoord.y/uScale,4.));
 float thr=float(b4[y*4+x])/16.;float bw=step(thr,lm);
 fragColor=vec4(mix(c.rgb,vec3(bw),uIntensity),c.a);
}"""
    const val FRAG_FAT_PIXEL = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uPixelSize,uGlitch,uTime,uJitter,uRGBSplit;
in vec2 vUV;out vec4 fragColor;
float hh(vec2 p){return fract(sin(dot(p,vec2(41.3,289.1)))*43758.5453);}
void main(){
 vec2 uv=vUV;vec2 gd=uResolution/uPixelSize;
 vec2 sn=floor(uv*gd)/gd+.5/gd;
 vec2 jt=(vec2(hh(sn+floor(uTime*30.)*.17+1.),hh(sn+floor(uTime*30.)*.17+2.))-.5)*uJitter/gd;
 sn+=jt*uGlitch;
 vec2 sp=vec2(uRGBSplit,0.)/uResolution*uGlitch;
 float r=texture(uTexture,sn+sp).r;
 float g=texture(uTexture,sn).g;
 float b=texture(uTexture,sn-sp).b;
 fragColor=vec4(r,g,b,1.);
}"""
    const val FRAG_HALFTONE = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uDotSize,uAngle,uIntensity;
in vec2 vUV;out vec4 fragColor;
void main(){
 vec2 uv=vUV;float ca=cos(uAngle),sa=sin(uAngle);
 vec2 ruv=vec2(ca*uv.x-sa*uv.y,sa*uv.x+ca*uv.y)*uResolution;
 vec2 cl=floor(ruv/uDotSize);vec2 ct=(cl+.5)*uDotSize;
 float dist=length(ruv-ct)/(uDotSize*.5);
 vec4 c=texture(uTexture,uv);float lm=dot(c.rgb,vec3(.2126,.7152,.0722));
 float dm=1.-smoothstep(lm-.05,lm+.05,dist);
 fragColor=vec4(mix(c.rgb,mix(vec3(.95),vec3(.05),dm),uIntensity),c.a);
}"""
    const val FRAG_CMYK_DOTS = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uDotSize,uIntensity;
in vec2 vUV;out vec4 fragColor;
float dotCmyk(vec2 uv,float ang,float cov,vec2 res,float sz){
 float ca=cos(ang),sa=sin(ang);vec2 ruv=vec2(ca*uv.x-sa*uv.y,sa*uv.x+ca*uv.y)*res;
 vec2 cl=floor(ruv/sz);vec2 ct=(cl+.5)*sz;
 float r=sqrt(clamp(cov,0.,1.));return 1.-smoothstep(r-.02,r+.02,length(ruv-ct)/(sz*.5));
}
void main(){
 vec4 c=texture(uTexture,vUV);
 float k=1.-max(c.r,max(c.g,c.b));
 float ck=(1.-c.r-k)/(1.-k+1e-4),mk=(1.-c.g-k)/(1.-k+1e-4),yk=(1.-c.b-k)/(1.-k+1e-4);
 float dc=dotCmyk(vUV,.2618,ck,uResolution,uDotSize);
 float dm=dotCmyk(vUV,1.308,mk,uResolution,uDotSize);
 float dy=dotCmyk(vUV,.7854,yk,uResolution,uDotSize);
 float dk=dotCmyk(vUV,0.,k,uResolution,uDotSize);
 vec3 o=vec3(1.);o.r*=1.-dc;o.g*=1.-dm;o.b*=1.-dy;o*=1.-dk;
 fragColor=vec4(mix(c.rgb,o,uIntensity),c.a);
}"""
    const val FRAG_TELETEXT = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uIntensity;
in vec2 vUV;out vec4 fragColor;
const vec3 pal[8]=vec3[8](vec3(0.),vec3(1.,0.,0.),vec3(0.,1.,0.),vec3(1.,1.,0.),vec3(0.,0.,1.),vec3(1.,0.,1.),vec3(0.,1.,1.),vec3(1.));
void main(){
 vec2 uv=vUV;vec2 gr=vec2(40.,25.);
 vec2 cl=floor(uv*gr);vec2 cuv=fract(uv*gr);
 vec3 c=texture(uTexture,(cl+.5)/gr).rgb;float bd=1e3;int bi=0;
 for(int i=0;i<8;i++){float d=length(c-pal[i]);if(d<bd){bd=d;bi=i;}}
 vec2 sx=floor(cuv*vec2(2.,3.));
 float on=step(.5,fract(sin(dot(cl+sx,vec2(12.9898,78.233)))*43758.5453));
 fragColor=vec4(mix(c,pal[bi]*on,uIntensity),1.);
}"""
    const val FRAG_BOKEH = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;
uniform float uIntensity,uSize,uThreshold;
in vec2 vUV;out vec4 fragColor;
float focusMask(float y){
 float fY=.5,fW=.18;
 float below=1.-smoothstep(fY-fW*2.5,fY-fW*.9,y);
 float above=smoothstep(fY+fW*.9,fY+fW*2.5,y);
 return clamp(max(below,above),0.,1.);
}
void main(){
 vec4 c=texture(uTexture,vUV);
 float m=focusMask(vUV.y);
 float rad=uSize*.35*m/max(uResolution.x,uResolution.y);
 vec3 acc=c.rgb;float wt=1.;
 for(int i=1;i<=10;i++){
  float t=float(i)/10.;
  float wdt=1.-t;
  vec2 o=vec2(0.,rad*t);
  acc+=(texture(uTexture,clamp(vUV+o,vec2(0.),vec2(1.))).rgb
      +texture(uTexture,clamp(vUV-o,vec2(0.),vec2(1.))).rgb)*wdt;
  wt+=2.*wdt;
 }
 if(m>0.01){
  vec2 h=vec2(rad*.55,0.);
  acc+=(texture(uTexture,clamp(vUV+h,vec2(0.),vec2(1.))).rgb
      +texture(uTexture,clamp(vUV-h,vec2(0.),vec2(1.))).rgb)*.6;
  wt+=1.2;
 }
 vec3 bl=acc/wt;
 float lm=dot(c.rgb,vec3(.2126,.7152,.0722));
 float hl=max(0.,lm-uThreshold);
 vec3 glow=bl*hl*uIntensity*(.5+m*1.5);
 fragColor=vec4(clamp(mix(c.rgb,bl,uIntensity*m)+glow,0.,1.4),c.a);
}"""
    const val FRAG_TERMINAL = """
#version 300 es
precision mediump float;
uniform sampler2D uTexture;uniform vec2 uResolution;uniform float uTime,uIntensity,uColorHue;
in vec2 vUV;out vec4 fragColor;
const float C=72.,R=40.;
const int F[23]=int[](
 1337,35017,23112,23093,35593,31277,31277,23057,35593,31277,
 23281,36009,13073,34961,31277,23057,
 0,2,2112,5376,448,7,455);
float hh(vec2 p){return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);}
vec3 pal(float h){
 vec3 g=vec3(.2,1.,.2),a=vec3(1.,.69,0.),w=vec3(.75),c=vec3(0.,1.,1.),r=vec3(1.,.2,.2);
 if(h<.2)return g;if(h<.4)return a;if(h<.6)return w;if(h<.8)return c;return r;
}
int chr(int c,int r,float t){
 int sc=int(floor(t/5.));int ro=r-int(mod(t*4.,24.));
 if(ro<0||ro>=R)return 16;
 if(ro<4){
  if(c<9){if(c<5)return 16;int d[]=int[](3,0,0,0,3,3,4,5,15,6,13,12);return d[int(mod(float(c+sc),12.))];}
  if(c<12)return 16;
  int h=int(mod(float(ro*99+c),36.));return h<16?h:16;
 }
 if(ro<8){
  int h=int(mod(float(ro*73+c*11+sc*37),36.));return h<16?h:16;
 }
 if(ro<12){
  if(c<3)return 16;
  int h=int(mod(float(ro*51+c*7+sc*13),20.))+16;return h<23?h:16;
 }
 if(ro<16){
  if(c<4)return 17;
  if(c>10&&c<14)return 16;
  int h=int(mod(float(ro*63+c*17+sc*53),20.))+16;return h<23?h:16;
 }
 if(ro<20){
  if(c<8)return 16;
  int a[10]=int[](3,3,4,5,15,6,13,12,14,0);
  int bi=(c-8)/6;if(bi<10)return a[bi];return 16;
 }
 if(ro<24){
  if(c==0)return 17;if(c==1)return 16;
  int a[6]=int[](3,0,5,5,8,9);
  int bi=(c-2)/12;if(bi<6)return a[bi];return 16;
 }
 if(ro<28){
  int h=int(mod(float(ro*47+c*23+sc*19),26.));return h<10?h:16;
 }
 if(ro<32){
  int h=int(mod(float(ro*31+c*13+sc*67),26.));return h<10?10+h:16;
 }
 if(ro<36){
  int h=int(mod(float(ro*43+c*29+sc*41),20.))+16;return h<23?h:16;
 }
 int cu=int(mod(uTime*3.,C*R/3));
 if(c==int(mod(float(cu),C))&&ro==R-1&&fract(uTime*3.)>.3)return 20;
 return 16;
}
void main(){
 vec2 gr=vec2(C,R);vec2 cl=floor(vUV*gr);vec2 cu=fract(vUV*gr);
 int ci=chr(int(cl.x),int(cl.y),uTime);
 int ma=F[ci];vec2 sb=floor(cu*vec2(3.,5.));
 int bi=int(sb.y)*3+int(sb.x);int bv=(ma>>bi)&1;
 vec3 fg=pal(uColorHue);vec3 bg=fg*vec3(0.,0.,.05);
 float on=float(bv);
 float sc=sb.y==4u?1.:.85+.15*sin(cu.y*3.14159);
 float fl=1.+.03*sin(uTime*23.+cl.x*7.+cl.y*13.);
 float sl=sin(vUV.y*uResolution.y*3.14159)*.25+.75;
 float gl=on*fg.r*.2;
 vec3 oc=mix(bg,fg*sc*fl,on)+gl;
 fragColor=vec4(mix(texture(uTexture,vUV).rgb,oc,uIntensity),1.);
}"""

    val PHASE_FRAME = mapOf("frame_overlay" to FRAG_FRAME_OVERLAY)

    val ALL_PHASE_1 = mapOf(
        "crt" to FRAG_CRT,
        "fisheye" to FRAG_FISHEYE,
        "vignette" to FRAG_VIGNETTE,
        "ca" to FRAG_CA,
        "grain" to FRAG_GRAIN,
        "dust" to FRAG_DUST,
        "vhs" to FRAG_VHS,
        "prism" to FRAG_PRISM,
        "kaleido" to FRAG_KALEIDO,
        "duotone" to FRAG_DUOTONE,
        "bleach" to FRAG_BLEACH,
        "cross" to FRAG_CROSS,
        "velvia" to FRAG_VELVIA,
        "portra800" to FRAG_PORTRA800,
        "winter" to FRAG_WINTER,
        "obsidian" to FRAG_OBSIDIAN,
        "warp" to FRAG_WARP,
        "soft_focus" to FRAG_SOFT_FOCUS
    )
    val ALL_PHASE_2 = mapOf(
        "bloom" to FRAG_BLOOM_COMP,
        "halation" to FRAG_HALATION_COMP,
        "mist" to FRAG_MIST,
        "tilt_shift" to FRAG_TILT_SHIFT,
        "dreamy" to FRAG_DREAMY,
        "anamorphic" to FRAG_ANAMORPHIC,
        "streak" to FRAG_STREAK
    )
    val ALL_PHASE_5 = mapOf(
        "glitch" to FRAG_GLITCH,
        "light_leak" to FRAG_LIGHT_LEAK,
        "double_exposure" to FRAG_DOUBLE_EXP,
        "wide_angle" to FRAG_WIDE_ANGLE,
        "bw_grain" to FRAG_BW_GRAIN,
        "lens_flare" to FRAG_LENS_FLARE,
        "light_rays" to FRAG_LIGHT_RAYS,
        "split_tone" to FRAG_SPLIT_TONE,
        "faded_film" to FRAG_FADED_FILM,
        "color_shift" to FRAG_COLOR_SHIFT,
        "starburst" to FRAG_STARBURST,
        "sharpen" to FRAG_SHARPEN,
        "focus_peak" to FRAG_FOCUS_PEAK,
        "false_color" to FRAG_FALSE_COLOR,
        "in_color" to FRAG_IN_COLOR,
        "1bit" to FRAG_1BIT,
        "fat_pixel" to FRAG_FAT_PIXEL,
        "halftone" to FRAG_HALFTONE,
        "cmyk_dots" to FRAG_CMYK_DOTS,
        "teletext" to FRAG_TELETEXT,
        "bokeh" to FRAG_BOKEH
    )

    val ALL_PHASE_6 = mapOf(
        "instant" to FRAG_INSTANT,
        "retro" to FRAG_RETRO,
        "terminal" to FRAG_TERMINAL
    )
    val ALL = ALL_PHASE_1 + ALL_PHASE_2 + ALL_PHASE_3 + ALL_PHASE_4 + ALL_PHASE_5 + ALL_PHASE_6 + PHASE_FRAME
}
