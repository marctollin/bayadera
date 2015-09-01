(ns uncomplicate.bayadera.mcmc.opencl.amd-gcn-stretch
  (:require [clojure.java.io :as io]
            [me.raynes.fs :as fsc]
            [uncomplicate.clojurecl
             [core :refer :all]
             [toolbox :refer [count-work-groups enq-reduce enq-read-long]]]
            [uncomplicate.neanderthal
             [core :refer [sum]]
             [opencl :refer [clv read!]]]
            [uncomplicate.neanderthal.opencl.amd-gcn :refer [gcn-single gcn-double]]
            [uncomplicate.bayadera.protocols :refer :all]))

(defprotocol MCMCEngineFactory
  (mcmc-engine [this walker-count cl-params]))

(deftype GCNStretch1D [ctx cqueue neanderthal-single neanderthal-double ^long walker-count wsize
                       ^long WGS ^ints step-counter
                       ^ints seed cl-params cl-xs cl-s0 cl-s1
                       cl-accept cl-accept-acc
                       stretch-move-odd-kernel stretch-move-even-kernel
                       init-walkers-kernel
                       sum-accept-reduction-kernel sum-accept-kernel
                       sum-means-kernel subtract-mean-kernel
                       autocovariance-kernel]
  Releaseable
  (release [_]
    (release cl-params)
    (release cl-xs)
    (release cl-s0)
    (release cl-s1)
    (release cl-accept)
    (release cl-accept-acc)
    (release stretch-move-odd-kernel)
    (release stretch-move-even-kernel)
    (release init-walkers-kernel)
    (release sum-accept-reduction-kernel)
    (release sum-accept-kernel)
    (release sum-means-kernel)
    (release subtract-mean-kernel)
    (release autocovariance-kernel))
  MCMC
  (init! [this]
    (do
      (reset-counters! this)
      (set-arg! init-walkers-kernel 0
                (doto seed (aset 0 (long (rand-int Integer/MAX_VALUE)))))
      (enq-nd! cqueue init-walkers-kernel (work-size [(/ walker-count 4)]))
      this))
  (reset-counters! [this]
    (do
      (enq-fill! cqueue cl-accept (int-array 1))
      (aset step-counter 0 0)
      this))
  (move! [this]
    (do
      (set-arg! stretch-move-odd-kernel 0
                (doto seed (aset 0 (long (rand-int Integer/MAX_VALUE)))))
      (set-arg! stretch-move-odd-kernel 6 (doto step-counter))
      (enq-nd! cqueue stretch-move-odd-kernel wsize)
      (set-arg! stretch-move-even-kernel 0
                (doto seed (aset 0 (long (rand-int Integer/MAX_VALUE)))))
      (set-arg! stretch-move-even-kernel 6 step-counter)
      (enq-nd! cqueue stretch-move-even-kernel wsize)
      (aset step-counter 0 (inc (aget step-counter 0)))
      cl-xs))
  (run-sampler! [this n]
    (let [means-count (long (count-work-groups WGS (/ walker-count 2)))
          max-lag 64
          i-max (- n max-lag)
          autocov-count (long (count-work-groups WGS i-max))]
      (with-release [cl-means-vec (clv neanderthal-single n)
                     cl-means (cl-buffer ctx (* Float/BYTES means-count n) :read-write)
                     cl-c0-vec (clv neanderthal-single autocov-count)
                     cl-d-vec (clv neanderthal-single autocov-count)]
        (reset-counters! this)
        (enq-fill! cqueue cl-means (float-array 1))
        (set-arg! stretch-move-odd-kernel 5 cl-means)
        (set-arg! stretch-move-even-kernel 5 cl-means)
        (dotimes [i n] (move! this))
        (set-args! sum-means-kernel 0 (.buffer cl-means-vec)
                   cl-means (int-array [means-count]))
        (enq-nd! cqueue sum-means-kernel (work-size [n]))
        (let [total-mean (/ (sum cl-means-vec) n)]
          (set-args! subtract-mean-kernel 0 (.buffer cl-means-vec)
                     (float-array [total-mean]))
          (enq-nd! cqueue subtract-mean-kernel (work-size [n]))

          (set-args! autocovariance-kernel 0 (.buffer cl-c0-vec)
                     (.buffer cl-d-vec) (.buffer cl-means-vec))
          (enq-nd! cqueue autocovariance-kernel (work-size [i-max]));;TODO magic number

          (let [c0 (/ (sum cl-c0-vec) i-max )
                d (/ (sum cl-d-vec) i-max)
                sigma (Math/sqrt (/ d n))
                tau (/ d c0)]
            {:c0 c0
             :d d
             :mean total-mean
             :sigma sigma
             :tau tau})))))
  (acc-rate [_]
    (if (pos? (aget step-counter 0))
      (do
        (enq-reduce cqueue sum-accept-kernel sum-accept-reduction-kernel
                    WGS (count-work-groups WGS (/ walker-count 2)))
        (/ (double (enq-read-long cqueue cl-accept-acc))
           (* walker-count (aget step-counter 0))))
      Double/NaN))
  (acor [_]
    :todo))

(deftype GCNStretch1DEngineFactory [ctx queue
                                    neanderthal-single neanderthal-double
                                    prog ^long WGS]
  Releaseable
  (release [_]
    (release prog)
    (release neanderthal-single)
    (release neanderthal-double))
  MCMCEngineFactory
  (mcmc-engine [_ walker-count params]
    (let [walker-count (long walker-count)
          cnt (long (/ walker-count 2))
          accept-count (count-work-groups WGS cnt)
          accept-acc-count (count-work-groups WGS accept-count)
          bytecount (long (* Float/BYTES cnt))
          seed (int-array 1)
          step-counter (int-array 1)
          cl-params (let [par-buf (cl-buffer ctx
                                             (* Float/BYTES
                                                (alength ^floats params))
                                             :read-only)]
                      (enq-write! queue par-buf params)
                      par-buf)
          cl-xs (cl-buffer ctx (* 2 bytecount) :read-write)
          cl-s0 (cl-sub-buffer cl-xs 0 bytecount :read-write)
          cl-s1 (cl-sub-buffer cl-xs bytecount bytecount :read-write)
          cl-accept (cl-buffer ctx (* Integer/BYTES accept-count) :read-write)
          cl-accept-acc (cl-buffer ctx (* Long/BYTES accept-acc-count) :read-write)]
      (->GCNStretch1D
       ctx queue neanderthal-single neanderthal-double
       walker-count (work-size [(/ walker-count 2)]) WGS step-counter
       seed cl-params cl-xs cl-s0 cl-s1
       cl-accept cl-accept-acc
       (doto (kernel prog "stretch_move1")
         (set-args! 1 cl-params cl-s0 cl-s1 cl-accept))
       (doto (kernel prog "stretch_move1")
         (set-args! 1 cl-params cl-s1 cl-s0 cl-accept))
       (doto (kernel prog "init_walkers") (set-arg! 1 cl-xs))
       (doto (kernel prog "sum_accept_reduction") (set-arg! 0 cl-accept-acc))
       (doto (kernel prog "sum_accept_reduce") (set-args! 0 cl-accept-acc cl-accept))
       (kernel prog "sum_means")
       (kernel prog "subtract_mean")
       (kernel prog "autocovariance")))))

(defn ^:private copy-random123 [include-name tmp-dir-name]
  (io/copy
   (io/input-stream
    (io/resource (format "uncomplicate/bayadera/mcmc/opencl/include/Random123/%s"
                         include-name)))
   (io/file (format "%s/Random123/%s" tmp-dir-name include-name))))

(defn gcn-stretch-1d-engine-factory [ctx cqueue]
  (let [tmp-dir-name (fsc/temp-dir "uncomplicate/")]
    (try
      (fsc/mkdirs (format "%s/%s" tmp-dir-name "Random123/features/"))
      (doseq [res-name ["philox.h" "array.h" "features/compilerfeatures.h"
                        "features/openclfeatures.h" "features/sse.h"]]
        (copy-random123 res-name tmp-dir-name))
      (let [neanderthal-single (gcn-single ctx cqueue)
            neanderthal-double (gcn-double ctx cqueue)]
        (->GCNStretch1DEngineFactory
         ctx cqueue neanderthal-single neanderthal-double
         (build-program!
          (program-with-source
           ctx
           [(slurp (io/resource "uncomplicate/clojurecl/kernels/reduction.cl"))
            (slurp (io/resource "uncomplicate/bayadera/mcmc/opencl/kernels/amd_gcn/random.h"))
            (slurp (io/resource "uncomplicate/bayadera/mcmc/opencl/kernels/amd_gcn/stretch-move.cl"))])
          (format "-cl-std=CL2.0 -DACCUMULATOR=float -I%s/" tmp-dir-name)
          nil)
         256))
      (finally
        (fsc/delete-dir tmp-dir-name)))))