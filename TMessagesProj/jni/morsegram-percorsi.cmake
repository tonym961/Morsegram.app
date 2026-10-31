# <morsegram>
# Toglie dai binari nativi i percorsi assoluti della macchina di chi compila.
#
# Perche' non basta passare i flag da Gradle: `CMakeLists.txt:7-8` e `voip/CMakeLists.txt:3` fanno
#     set(CMAKE_CXX_FLAGS "-DANDROID -g")
# cioe' SOVRASCRIVONO la variabile invece di aggiungere, quindi cFlags/cppFlags di AGP (che
# inizializzano CMAKE_C_FLAGS/CMAKE_CXX_FLAGS) vengono cancellati prima di compilare una riga.
# Misurato: con quei flag in build.gradle la libreria uscita conteneva gli stessi 427 percorsi
# /Users/tony/... della 0.1.15, identici al byte.
#
# `add_compile_options` invece scrive la proprieta' di directory COMPILE_OPTIONS, che un
# `set(CMAKE_CXX_FLAGS ...)` non tocca e che i sottoprogetti ereditano al momento della
# `add_subdirectory`. Per questo i 427 percorsi di voip (401) e tgcalls (26) vengono coperti
# nonostante la riga 3 di voip.
#
# Entra in gioco con -DCMAKE_PROJECT_INCLUDE=<questo file> fra gli `arguments` del blocco cmake di
# TMessagesProj/build.gradle: zero righe dentro i CMakeLists di upstream.
#
# /jni e' lo stesso nome virtuale di REPRODUCIBLE_SOURCE_ROOT in jni/prebuild/common.sh:76, cosi' i
# pezzi precompilati e quelli compilati da qui parlano di percorsi coerenti dentro la stessa .so.
# </morsegram>

set(MORSEGRAM_PATH_MAP "${CMAKE_CURRENT_LIST_DIR}=/jni")

add_compile_options(
        "-ffile-prefix-map=${MORSEGRAM_PATH_MAP}"
        "-fdebug-prefix-map=${MORSEGRAM_PATH_MAP}"
        "-fmacro-prefix-map=${MORSEGRAM_PATH_MAP}")

message(STATUS "morsegram: percorsi mappati ${MORSEGRAM_PATH_MAP}")
