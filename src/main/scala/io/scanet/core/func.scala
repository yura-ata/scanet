package io.scanet.core

import breeze.linalg.{*, DenseMatrix, DenseVector, max, sum}
import breeze.numerics.{abs, log, pow, signum}

object func {

  /**
    * Function which always returns 0 result
    */
  case class Zero()

  def zero: DFBuilder[Zero] = coef => Zero()

  trait ZeroFunctionInst {

    implicit def zeroInst: DiffFunction[Zero] = new DiffFunction[Zero] {

      override def apply(f: Zero, vars: DenseVector[Double]): Double = 0.00

      override def gradient(f: Zero, vars: DenseVector[Double]): DenseVector[Double] =
        DenseVector.zeros(vars.length)
    }
  }

  /**
    * Linear function: `k0*x0 + k1*x1 + .. + kn*xn`
    */
  case class Linear(coef: DenseVector[Double])

  /**
    * NOTE: in `coef` argument we use only the first row
    */
  def linear: DFBuilder[Linear] = coef => Linear(coef(0, ::).t)

  trait LinearFunctionInst {

    implicit def linearFunctionInst: DiffFunction[Linear] = new DiffFunction[Linear] {

      override def arity(f: Linear): Int = f.coef.length

      override def apply(f: Linear, vars: DenseVector[Double]): Double = f.coef.t * vars

      override def gradient(f: Linear, vars: DenseVector[Double]): DenseVector[Double] = f.coef
    }
  }

  /**
    * Polynomial function: `(k0*x0^0 + k1*x1^0 + .. + kn*xn^0) + (k0*x0^1 + k1*x1^1 + .. + kn*xn^1) + (k0*x0^m + k1*x1^m + .. + kn*xn^m)`
    */
  case class Polynomial(coef: DenseMatrix[Double])

  /**
    * NOTE: each row in `coef` increases the exponent
    */
  def polynomial: DFBuilder[Polynomial] = coef => Polynomial(coef)

  trait PolynomialFunctionInst {

    implicit def polynomialFunctionInst: DiffFunction[Polynomial] = new DiffFunction[Polynomial] {

      override def arity(f: Polynomial): Int = f.coef.cols

      override def apply(f: Polynomial, vars: DenseVector[Double]): Double = {
        var rows: Seq[Double] =
          for (i <- 0 until f.coef.rows)
            yield f.coef(i, ::) * pow(vars, i)
        sum(new DenseVector[Double](rows.toArray))
      }

      override def gradient(f: Polynomial, vars: DenseVector[Double]): DenseVector[Double] = {
        val varsExp = f.coef.mapPairs {
          case ((i, j), v) =>
            v * i * pow(vars(j), max(0, i - 1))
        }
        sum(varsExp.t(*, ::))
      }
    }
  }

  /**
    * Coefficients for very simple 1-dimensional convex function
    */
  def `x0^2`: DenseMatrix[Double] = DenseMatrix(0.0, 0.0, 1.0)

  /**
    * Coefficients for very simple 2-dimensional convex function
    */
  def `x0^2 + 5*x1^2 + 10`: DenseMatrix[Double] = DenseMatrix((0.0, 10.0), (0.0, 0.0), (1.0, 5.0))

  /**
    * Coefficients for 2-dimensional non-convex function with saddle point at (0, 0) and two minimums (-1, 0), (1, 0)
    */
  def `x0^4 - 2*x0^2 + x1^2`: DenseMatrix[Double] = DenseMatrix((0.0, 0.0), (0.0, 0.0), (-2.0, 1.0), (0.0, 0.0), (1.0, 0.0))

  /**
    * TODO: create a separate page
    *
    * Linear regression. Function which calculates a mean squared error of a set of linear functions which parametrized
    * by `X` parameters matrix and has a vector of expected values `y` (in pseudo code):
    * {{{
    *   fe(x, y, t) = 1/2m * sum(i = 0..m)(f(xi, t) - yi)^2
    * }}}
    * where:
    * - `f` is a regular linear function: `f(xi, t) = sum(j = 0..n)(xij * tj)`
    * - `xij` - the value af a `j-th` linear function parameter for the `i` data-set
    * - `tj` - the value of `j-th` linear function argument
    * - `yi` the value af an expected linear function result for the `i` data-set
    * - `n` - number of vars in a function
    * - `m` - data-set size
    *
    * In a matrix form it will be:
    * {{{
    *   fe(x, y, t) = 1/2m * sum(X * transpose(t) - y)^2
    * }}}
    */
  case class LinearRegression(coef: DenseMatrix[Double])

  /**
    * NOTE: each row in `coef` increases the exponent
    */
  def linearRegression: DFBuilder[LinearRegression] = coef => LinearRegression(coef)

  trait LinearRegressionFunctionInst {

    implicit def linearRegressionFunctionInst: DiffFunction[LinearRegression] = new DiffFunction[LinearRegression] {

      /**
        * Calculate
        * @return a result of a function (error)
        */
      override def apply(f: LinearRegression, vars: DenseVector[Double]): Double = {
        val (xs, y) = splitXY(f.coef)
        0.5/(f.coef.rows: Double) * sum(pow(xs * vars - y, 2))
      }

      /**
        * A gradient of the function `fe` with respect to `t`.
        * For now let's take a partial derivative of the first `t1`:
        * {{{
        *   fe(x, y, t) = 1/2m * ((x11 * t1 + ... + x1n * tn - y1)^2 + (x21 * t1 + ... + x2n * tn - y2)^2 + ...)
        *   fe(x, y, t)/dt1 = 1/2m * (2 * x11 * (x11 * t1 + ... + x1n * tn - y1) + 2 * x21 * (x21 * t1 + ... + x2n * tn - y2) + ...)
        * }}}
        *
        * Simplified:
        * {{{
        *   fe(x, y, t)/dt1 = 1/m * (x11 * (f(x1, t) - y1) + x21 * (f(x2, t) - y2) + ...)
        * }}}
        *
        * In a matrix form it will be:
        * {{{
        *   fe(x, y, t)/dt1 = 1/m * (transpose(column(X, 1)) * (X * transpose(t) - y))
        * }}}
        *
        * Let's generalize it, we can calculate partial derivative for each `ti` in one operation,
        * for that we can take all `X` columns instead of just one:
        * {{{
        *   grad(fe, t) = 1/m * (transpose(X) * (X * transpose(t) - y))
        * }}}
        * @return gradient vector
        */
      override def gradient(f: LinearRegression, vars: DenseVector[Double]): DenseVector[Double] = {
        val (xs, y) = splitXY(f.coef)
        val fx = xs * vars
        1.0/f.coef.rows * (xs.t * (fx - y))
      }

      override def arity(f: LinearRegression): Int = f.coef.cols - 1
    }
  }

  /**
    * TODO: create a separate page
    *
    * Logistic regression
    */
  case class LogisticRegression(coef: DenseMatrix[Double])

  def logisticRegression: DFBuilder[LogisticRegression] = coef => LogisticRegression(coef)

  trait LogisticRegressionFunctionInst {

    implicit def logisticRegressionFunctionInst: DiffFunction[LogisticRegression] = new DiffFunction[LogisticRegression] {

      override def apply(f: LogisticRegression, vars: DenseVector[Double]): Double = {
        val (xs, y) = splitXY(f.coef)
        val s = breeze.numerics.sigmoid(xs * vars)
        (1.0/y.length) * sum(-y *:* log(s) - (1.0 - y) *:* log(1.0 - s))
      }

      override def gradient(f: LogisticRegression, vars: DenseVector[Double]): DenseVector[Double] = {
        val (xs, y) = splitXY(f.coef)
        val s = breeze.numerics.sigmoid(xs * vars)
        1.0/f.coef.rows * (xs.t * (s - y))
      }

      override def arity(f: LogisticRegression): Int = f.coef.cols - 1
    }
  }

  def splitXY(coef: DenseMatrix[Double]): (DenseMatrix[Double], DenseVector[Double]) = {
    val xs = coef(::, 0 until coef.cols - 1)
    val y = coef(::, coef.cols - 1)
    (xs, y)
  }

  /**
    * L1 regularization. Known as Lasso Regression (Least Absolute Shrinkage and Selection Operator).
    * Computes “absolute value of magnitude” of coefficients.
    *
    * @param lambda regularization coefficient
    * @param ignoreFirst ignore regularization for first variable
    */
  case class L1(lambda: Double = 1.0, ignoreFirst: Boolean = false)

  def l1(lambda: Double = 1.0, ignoreFirst: Boolean = false): DFBuilder[L1] = coef => L1(lambda, ignoreFirst)

  trait L1FunctionInst {

    implicit def l1FunctionInst: DiffFunction[L1] = new DiffFunction[L1] {

      override def apply(f: L1, vars: DenseVector[Double]): Double = {
        val varsI = if (f.ignoreFirst) vars(1 until vars.length) else vars
        (f.lambda/2) * sum(abs(varsI))
      }

      override def gradient(f: L1, vars: DenseVector[Double]): DenseVector[Double] = {
        val varsI = if (f.ignoreFirst) DenseVector.vertcat(DenseVector(0.0), vars(1 until vars.length)) else vars
        f.lambda * signum(varsI)
      }
    }
  }

  /**
    * L2 regularization. Known as Ridge Regression.
    * Computes squared magnitude of coefficients.
    *
    * @param lambda regularization coefficient
    * @param ignoreFirst ignore regularization for first variable
    */
  case class L2(lambda: Double = 1.0, ignoreFirst: Boolean = false)

  def l2(lambda: Double = 1.0, ignoreFirst: Boolean = false): DFBuilder[L2] = coef => L2(lambda, ignoreFirst)

  trait L2FunctionInst {

    implicit def l2FunctionInst: DiffFunction[L2] = new DiffFunction[L2] {

      override def apply(f: L2, vars: DenseVector[Double]): Double = {
        val varsI = if (f.ignoreFirst) vars(1 until vars.length) else vars
        (f.lambda/2) * sum(pow(varsI, 2))
      }

      override def gradient(f: L2, vars: DenseVector[Double]): DenseVector[Double] = {
        val varsI = if (f.ignoreFirst) DenseVector.vertcat(DenseVector(0.0), vars(1 until vars.length)) else vars
        f.lambda * varsI
      }
    }
  }

  case class Sigmoid()

  trait SigmoidInst {

    implicit def sigmoidFunctionInst: DiffFunction[Sigmoid] = new DiffFunction[Sigmoid] {

      override def apply1(f: Sigmoid, vars1: Double): Double = breeze.numerics.sigmoid(vars1)

      override def apply1(f: Sigmoid, vars1: DenseVector[Double]): DenseVector[Double] = breeze.numerics.sigmoid(vars1)

      override def apply1(f: Sigmoid, vars1: DenseMatrix[Double]): DenseMatrix[Double] = breeze.numerics.sigmoid(vars1)

      override def gradient1(f: Sigmoid, vars1: Double): Double = {
        val s = breeze.numerics.sigmoid(vars1)
        s - pow(s, 2)
      }

      override def gradient1(f: Sigmoid, vars1: DenseVector[Double]): DenseVector[Double] = {
        val s = breeze.numerics.sigmoid(vars1)
        s - pow(s, 2)
      }

      override def gradient1(f: Sigmoid, vars1: DenseMatrix[Double]): DenseMatrix[Double] = {
        val s = breeze.numerics.sigmoid(vars1)
        s - pow(s, 2)
      }

      override def apply(f: Sigmoid, vars: DenseVector[Double]): Double = apply1(f, vars(0))

      override def gradient(f: Sigmoid, vars: DenseVector[Double]): DenseVector[Double] = DenseVector(gradient1(f, vars(0)))
    }
  }
}
