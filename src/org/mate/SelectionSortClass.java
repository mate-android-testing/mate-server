package org.mate;

/**
 * Created by marceloeler on 15/11/17.
 */
public class SelectionSortClass {


    public void selectionSort(int[] array) {
        if (array==null)
            return;
        for (int fixo = 0; fixo < array.length - 1; fixo++) {
            int menor = fixo;
            for (int i = menor + 1; i < array.length; i++) {
                if (array[i] < array[menor]) {
                    menor = i;
                }
            }
            if (menor != fixo) {
                int t = array[fixo];
                array[fixo] = array[menor];
                array[menor] = t;
            }
        }
    }



    public static void main(String[] args){
        int vet[] = new int[5];
        vet[0]=10;
        vet[1]=11;
        vet[2]=5;
        vet[3]=2;
        vet[4]=1;
        SelectionSortClass ss = new SelectionSortClass();
        ss.selectionSort(null);
        int vetw[] = new int[0];
        ss.selectionSort(vetw);
        for (int i=0; i<vet.length; i++)
            System.out.println(vet[i]);
    }
}
