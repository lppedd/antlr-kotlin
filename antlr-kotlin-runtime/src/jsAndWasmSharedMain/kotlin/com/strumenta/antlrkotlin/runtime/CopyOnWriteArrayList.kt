package com.strumenta.antlrkotlin.runtime

// Note(Edoardo): JS is single threaded, so a normal list is good enough
public actual typealias CopyOnWriteArrayList<E> = ArrayList<E>
