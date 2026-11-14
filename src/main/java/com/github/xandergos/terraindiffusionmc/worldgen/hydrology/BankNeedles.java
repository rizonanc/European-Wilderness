package com.github.xandergos.terraindiffusionmc.worldgen.hydrology;

/** Lower thin diagonal dry remnants inside a single water level; never grow recursively. */
final class BankNeedles {
    static void lower(int side,int[] air,int[] water) {
        int[] original=water.clone();
        for(int z=PreparedWaterArea.COLLAR;z<side-PreparedWaterArea.COLLAR;z++)
            for(int x=PreparedWaterArea.COLLAR;x<side-PreparedWaterArea.COLLAR;x++) {
                int p=z*side+x;if(original[p]!=PreparedWaterArea.NONE)continue;
                int level=PreparedWaterArea.NONE,count=0;boolean different=false;
                for(int direction=0;direction<4;direction++) {
                    int q=p+switch(direction){case 0->-1;case 1->1;case 2->-side;default->side;};
                    int v=original[q];if(v==PreparedWaterArea.NONE)continue;
                    if(level!=PreparedWaterArea.NONE&&v!=level){different=true;break;}
                    level=v;count++;
                }
                if(!different&&count>=3&&air[p]<=level+2&&air[p]>=level) {
                    water[p]=level;air[p]=level;
                }
            }
    }
    private BankNeedles(){}
}
