package org.nd4j.autodiff.functions;

import org.nd4j.autodiff.Field;

public interface VectorDifferential<X extends Field<X>, D> {

    public D diff(VariableVector<X> i_v);
}