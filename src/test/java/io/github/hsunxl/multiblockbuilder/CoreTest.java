package io.github.hsunxl.multiblockbuilder;

import java.util.HashSet;

public final class CoreTest {
    public static void main(String[] args) throws Exception {
        int cases=0;
        for(int w=1;w<=16;w++) for(int d=1;d<=16;d++) for(int turns=0;turns<4;turns++) {
            var seen=new HashSet<Geometry.Point>();
            for(int x=0;x<w;x++) for(int z=0;z<d;z++) {
                var p=Geometry.rotate(x,3,z,w,d,turns);
                int rw=turns%2==0?w:d,rd=turns%2==0?d:w;
                assert p.x()>=0&&p.x()<rw&&p.z()>=0&&p.z()<rd&&p.y()==3;
                assert seen.add(p):"rotation must be bijective";
                var q=Geometry.rotate(p.x(),p.y(),p.z(),rw,rd,4-turns);
                assert q.equals(new Geometry.Point(x,3,z)):"round-trip";
                cases++;
            }
        }
        System.out.println("PASS "+cases+" rotation/bounds/round-trip cases");
        GuideImportTest.run();
    }
}
