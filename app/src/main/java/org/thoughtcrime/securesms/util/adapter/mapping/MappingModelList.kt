package org.thoughtcrime.securesms.util.adapter.mapping



class MappingModelList : ArrayList<MappingModel<*>?> {
    constructor() {}
    constructor(c: Collection<MappingModel<*>?>) : super(c) {}

    companion object {
        @JvmStatic
        fun singleton(model: MappingModel<*>): MappingModelList {
            val list = MappingModelList()
            list.add(model)
            return list
        }
    }
}