import os,shutil,sys
J='/tmp/jdk/'
out=sys.argv[1]
def pom(g,a,v,deps=()):
    d=''.join('<dependency><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>%s</dependency>'%(dg,da,dv,('<scope>%s</scope>'%sc if sc else '')) for dg,da,dv,sc in deps)
    return '<project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version><dependencies>%s</dependencies></project>'%(g,a,v,d)
def put(g,a,v,jar=None,deps=()):
    d=os.path.join(out,g.replace('.','/'),a,v); os.makedirs(d,exist_ok=True)
    open(os.path.join(d,'%s-%s.pom'%(a,v)),'w').write(pom(g,a,v,deps))
    if jar: shutil.copy(jar,os.path.join(d,'%s-%s.jar'%(a,v)))
G='com.google.auto'
put('com.google.guava','guava','31.0.1-jre')                       # requested by auto-value; jar not needed: 32 wins
put('com.google.guava','guava','32.0.1-jre',J+'deb-libguava-java/usr/share/java/guava-32.0.1-jre.jar')
put('com.squareup','javapoet','1.13.0',J+'deb-libjavapoet-java/usr/share/java/javapoet-1.13.0.jar')
put('com.google.escapevelocity','escapevelocity','0.9.1',J+'deb-libescapevelocity-java/usr/share/java/escapevelocity-0.9.1.jar')
put('com.google.errorprone','error_prone_annotations','2.18.0',J+'deb-liberror-prone-java/usr/share/maven-repo/com/google/errorprone/error_prone_annotations/2.18.0/error_prone_annotations-2.18.0.jar')
put(G,'auto-common','1.1.2',J+'deb-libgoogle-auto-common-java/usr/share/java/auto-common-1.1.2.jar',[('com.google.guava','guava','32.0.1-jre','')])
put(G+'.service','auto-service-annotations','1.0.1',J+'deb-libgoogle-auto-service-java/usr/share/java/auto-service-annotations-1.0.1.jar')
put(G+'.service','auto-service','1.0.1',J+'deb-libgoogle-auto-service-java/usr/share/java/auto-service-1.0.1.jar',
    [(G+'.service','auto-service-annotations','1.0.1',''),(G,'auto-common','1.1.2',''),('com.google.guava','guava','32.0.1-jre','')])
put(G+'.value','auto-value-annotations','1.8.2',J+'deb-libgoogle-auto-value-java/usr/share/java/auto-value-annotations-1.8.2.jar')
put(G+'.value','auto-value','1.8.2',J+'deb-libgoogle-auto-value-java/usr/share/java/auto-value-1.8.2.jar',
    [(G,'auto-common','1.1.2',''),(G+'.service','auto-service-annotations','1.0.1',''),('com.google.errorprone','error_prone_annotations','2.18.0',''),
     ('com.google.escapevelocity','escapevelocity','0.9.1',''),('com.google.guava','guava','31.0.1-jre',''),('com.squareup','javapoet','1.13.0','')])

# managed-scope fixture: dependencyManagement says "junk" is test scope; the child declares no scope (Maven: test -> not resolved)
os.makedirs(os.path.join(out,'com/example/parent/1'),exist_ok=True)
open(os.path.join(out,'com/example/parent/1/parent-1.pom'),'w').write('<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><dependencyManagement><dependencies><dependency><groupId>com.example</groupId><artifactId>junk</artifactId><version>1</version><scope>test</scope></dependency></dependencies></dependencyManagement></project>')
d=os.path.join(out,'com/example/mgd/1.0'); os.makedirs(d,exist_ok=True)
open(os.path.join(d,'mgd-1.0.pom'),'w').write('<project><modelVersion>4.0.0</modelVersion><parent><groupId>com.example</groupId><artifactId>parent</artifactId><version>1</version></parent><groupId>com.example</groupId><artifactId>mgd</artifactId><version>1.0</version><dependencies><dependency><groupId>com.example</groupId><artifactId>junk</artifactId></dependency></dependencies></project>')
shutil.copy(J+'deb-libjavapoet-java/usr/share/java/javapoet-1.13.0.jar',os.path.join(d,'mgd-1.0.jar'))
