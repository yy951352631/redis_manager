package com.shcj.cache.common;

import java.util.Objects;

/**
 * Description
 *
 * @author zoushunqing 2023/2/21 14:19
 * @since Dev_1.0.1
 */
public class Tuple<V1, V2> {

    public static <V1, V2> Tuple<V1, V2> tuple(V1 v1, V2 v2) {
        return new Tuple<V1, V2>(v1, v2);
    }

    private V1 v1;
    private V2 v2;

    public Tuple(V1 v1, V2 v2) {
        this.v1 = v1;
        this.v2 = v2;
    }

    public V1 v1() {
        return v1;
    }

    public V2 v2() {
        return v2;
    }

    public void set(V1 v1, V2 v2) {
        this.v1 = v1;
        this.v2 = v2;
    }

    public void setV1(V1 v1) {
        this.v1 = v1;
    }

    public void setV2(V2 v2) {
        this.v2 = v2;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Tuple tuple = (Tuple) o;

        if (!Objects.equals(v1, tuple.v1)) {
            return false;
        }
        if (!Objects.equals(v2, tuple.v2)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = v1 != null ? v1.hashCode() : 0;
        result = 31 * result + (v2 != null ? v2.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "Tuple [v1=" + v1 + ", v2=" + v2 + "]";
    }
}

