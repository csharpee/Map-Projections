/**
 * MIT License
 * 
 * Copyright (c) 2017 Justin Kunimune
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package apps;

import java.io.File;
import java.util.Arrays;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.LineChart.SortingPolicy;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart.Data;
import javafx.scene.chart.XYChart.Series;
import javafx.stage.Stage;
import maps.Projection;
import util.Math2;

/**
 * An application to compare and optimize map projections
 * 
 * @author Justin Kunimune
 */
public class MapOptimizer extends Application {
	
	
	private static final Projection[] EXISTING_PROJECTIONS = { Projection.HOBODYER, Projection.ROBINSON,
			Projection.EQUIRECTANGULAR, Projection.LEE };
	private static final double[] WEIGHTS = { .083, .20, .33, .50, .71, 1.0, 1.4, 2.0, 3.0, 5.0, 12. };
	private static final int NUM_DESCENT = 40;
	private LineChart<Number, Number> chart;
	
	
	
	public static final void main(String[] args) {
		launch(args);
	}
	
	
	@Override
	public void start(Stage stage) throws Exception {
		final long startTime = System.currentTimeMillis();
		
		chart = new LineChart<Number, Number>(
				new NumberAxis("Size distortion", 0, 1, 0.2),
				new NumberAxis("Shape distortion", 0, 1, 0.2));
		chart.setCreateSymbols(true);
		chart.setAxisSortingPolicy(SortingPolicy.NONE);
		double[][][] globe = Projection.globe(0.02);
		
		chart.getData().add(analyzeAll(globe, EXISTING_PROJECTIONS));
		chart.getData().add(optimizeHyperelliptical(globe));
//		chart.getData().add(optimizeEACylinder(globe));
		chart.getData().add(optimizeTetrapower(globe));
		chart.getData().add(optimizeTetrafillet(globe));
		
		System.out.println("Total time elapsed: "+
				(System.currentTimeMillis()-startTime)/1000.+"s");
		
		stage.setTitle("Map Projections");
		stage.setScene(new Scene(chart));
		
		ImageIO.write(
				SwingFXUtils.fromFXImage(chart.snapshot(null, null), null),
				"png", new File("output/graph.png"));
		
		stage.show();
	}
	
	
	private static Series<Number, Number> analyzeAll(double[][][] points,
			Projection... projs) { //analyze and plot the specified preexisting map projections
		System.out.println("Analyzing "+Arrays.toString(projs));
		Series<Number, Number> output = new Series<Number, Number>();
		output.setName("Basic Projections");
		
		for (Projection proj: projs)
			output.getData().add(plotDistortion(points, proj::project));
		
		return output;
	}
	
	
	private static Series<Number, Number> optimizeFamily(BinaryOperator<double[]> projectionFam, String name,
			double[][] bounds, double[][][] points) { // optimize and plot some maps of a given family maps
		System.out.println("Optimizing "+name);
		final double[][] currentBest = new double[WEIGHTS.length][3+bounds.length]; //the 0-3 cols are the min distortions for each weight, the other cols are the values of k and n that caused that
		for (int k = 0; k < WEIGHTS.length; k ++)
			currentBest[k][0] = Integer.MAX_VALUE;
		
		final double[] params = new double[bounds.length];
		for (int i = 0; i < params.length; i ++)	params[i] = bounds[i][0]; //initialize params
		
		while (true) { //start with brute force
			int i;
			for (i = 0; i < params.length; i ++) { //iterate the parameters
				if (params[i] < bounds[i][1]) {
					for (int j = 0; j < i; j ++)
						params[j] = bounds[j][0];
					params[i] += (bounds[i][1]-bounds[i][0])/Math.floor(Math.pow(16, 1./params.length))*.99999999;
					break;
				}
			}
			if (i == params.length)	break; //if you made it through the for loop without breaking (finding a parameter to increment), you're done!
//			System.out.println(Arrays.toString(params));
			
			double[] distortions = avgDistortion(projectionFam, params, points);
			for (int k = 0; k < WEIGHTS.length; k ++) {
				final double avgDist = Math.pow(distortions[0],1.5) +
						WEIGHTS[k]*Math.pow(distortions[1],1.5);
				if (avgDist < currentBest[k][0]) {
					currentBest[k][0] = avgDist;
					currentBest[k][1] = distortions[0];
					currentBest[k][2] = distortions[1];
					System.arraycopy(params,0, currentBest[k],3, params.length);
				}
			}
		}
		
		final double h = 1e-7;
		final double delX = 5e-2;
		for (int k = 0; k < WEIGHTS.length; k ++) { //now do gradient descent
			System.arraycopy(currentBest[k],3, params,0, params.length);
//			System.out.println("Starting gradient descent with weight "+WEIGHTS[k]+" and initial parameters "+Arrays.toString(params));
			double fr0 = currentBest[k][0];
			double[] frd = new double[params.length];
			for (int i = 0; i < NUM_DESCENT; i ++) {
				if (i > 0)
					fr0 = avgDistortion( //calculate the distortion here
							projectionFam, params, points, WEIGHTS[k]);
//				System.out.println(Arrays.toString(params)+" -> "+fr0);
				for (int j = 0; j < params.length; j ++) {
					params[j] += h;
					frd[j] = avgDistortion(projectionFam, params, points, WEIGHTS[k]); //and the distortion nearby
					params[j] -= h;
				}
				for (int j = 0; j < params.length; j ++)
					params[j] -= (frd[j]-fr0)/h*delX; //use that to approximate the gradient and go in that direction
			}
			System.arraycopy(params,0, currentBest[k],3, params.length);
			System.arraycopy(avgDistortion(projectionFam, params, points), 0,
					currentBest[k], 1, 2);
		}
		
		final Series<Number, Number> output = new Series<Number, Number>();
		output.setName(name);
		
		System.out.println("We got the best "+name+" projections using:");
		for (double[] best: currentBest) {
			System.out.print("\t");
			for (int i = 0; i < params.length; i ++)
				System.out.print("t"+i+"="+best[3+i]+"; ");
			System.out.println("\t("+best[1]+", "+best[2]+")");
			output.getData().add(new Data<Number, Number>(best[1], best[2]));
		}
		return output;
	}
	
	
	private static Series<Number, Number> optimizeHyperelliptical(double[][][] points) { //optimize and plot some hyperelliptical maps
		return optimizeFamily(MapOptimizer::hyperelliptical, "Hyperelliptic",
				new double[][] {{2.5,5}, {0.5,1.75}, {1.0,2.0}}, points);
	}
	
	
	private static Series<Number, Number> optimizeTetrapower(double[][][] points) { //optimize and plot some hyperelliptical maps
		return optimizeFamily(MapOptimizer::tetrapower, "Tetrapower",
				new double[][] {{0.25,2.25}, {0.25,2.25}, {.25,2.25}}, points);
	}
	
	
	private static Series<Number, Number> optimizeTetrafillet(double[][][] points) { //optimize and plot some hyperelliptical maps
		return optimizeFamily(MapOptimizer::tetrafillet, "Tetrafillet",
				new double[][] {{0.25,2.25}, {0.25,2.25}, {.25,2.25}}, points);
	}
	
	
	private static Data<Number, Number> plotDistortion(double[][][] pts, UnaryOperator<double[]> xform) {
		double[][][] distortion = Projection.calculateDistortion(pts, xform);
		return new Data<Number, Number>(
				Math2.stdDev(distortion[0]),
				Math2.mean(distortion[1]));
	}
	
	
	private static final double[] hyperelliptical(double[] coords, double[] params) { //a hyperelliptic map projection with hyperellipse order k and lattitudinal spacind described by x^n/n
		final double lat = coords[0], lon = coords[1];
		final double k = params[0], n = params[1], a = params[2];
		
		return new double[] {
				Math.pow(1 - Math.pow(Math.abs(lat/(Math.PI/2)), k),1/k)*lon,
				(1-Math.pow(1-Math.abs(lat/(Math.PI/2)), n))/Math.sqrt(n)*Math.signum(lat)*Math.PI/2*a};
	}
	
	
	private static final double[] tetrapower(double[] coords, double[] params) { //a tetragraph projection using a few power functions to spice it up
		final double k1 = params[0], k2 = params[1], k3 = params[2];
		
		return Projection.tetrahedralProjectionForward(coords[0], coords[1], (coordR) -> {
			final double t0 = Math.floor(coordR[1]/(2*Math.PI/3))*(2*Math.PI/3) + Math.PI/3;
			final double tht = coordR[1] - t0;
			final double thtP = Math.PI/3*(1 - Math.pow(1-Math.abs(tht)/(Math.PI/2),k1))/(1 - 1/Math.pow(3,k1))*Math.signum(tht);
			final double kRad = k3*Math.abs(thtP)/(Math.PI/3) + k2*(1-Math.abs(thtP)/(Math.PI/3));
			final double rmax = .5/Math.cos(thtP); //the max normalized radius of this triangle (in the plane)
			final double rtgf = Math.atan(1/Math.tan(coordR[0])*Math.cos(tht))/Math.atan(Math.sqrt(2))*rmax; //normalized tetragraph radius
			return new double[] {
					(1 - Math.pow(1-rtgf,kRad))/(1 - Math.pow(1-rmax,kRad))*rmax*2*Math.PI/3,
					thtP + t0
			};
		});
	}
	
	
	private static final double[] tetrafillet(double[] coords, double[] params) { //a tetragraph projection using a few power functions to spice it up and now with fillets
		final double k1 = params[0], k2 = params[1], k3 = params[2];
		
		return Projection.tetrahedralProjectionForward(coords[0], coords[1], (coordR) -> {
			final double t0 = Math.floor(coordR[1]/(2*Math.PI/3))*(2*Math.PI/3) + Math.PI/3;
			final double tht = coordR[1] - t0;
			final double thtP = Math.PI/3*(1 - Math.pow(1-Math.abs(tht)/(Math.PI/2),k1))/(1 - 1/Math.pow(3,k1))*Math.signum(tht);
			final double kRad = k3*Math.abs(thtP)/(Math.PI/3) + k2*(1-Math.abs(thtP)/(Math.PI/3));
			final double rmax = 1/2. + 1/4.*Math.pow(thtP,2) + 5/48.*Math.pow(thtP,4) - .132621*Math.pow(thtP,6); //the max normalized radius of this triangle (in the plane)
			final double rtgf = Math.atan(1/Math.tan(coordR[0])*Math.cos(tht))/Math.atan(Math.sqrt(2))*rmax; //normalized tetragraph radius
			return new double[] {
					(1 - Math.pow(1-rtgf,kRad))/(1 - Math.pow(1-rmax,kRad))*rmax*2*Math.PI/3,
					thtP + t0
			};
		});
	}
	
	
	private static double[] avgDistortion(BinaryOperator<double[]> projFam,
			double[] params, double[][][] points) {
		final double[][][] distDist = Projection.calculateDistortion(points,
				(coords) -> projFam.apply(coords, params)); //distortion distribution
		return new double[] { Math2.stdDev(distDist[0]), Math2.mean(distDist[1]) };
	}
	
	
	private static double avgDistortion(BinaryOperator<double[]> projFam,
			double[] params, double[][][] points, double weight) {
		final double[] distortions = avgDistortion(projFam, params, points);
		return Math.pow(distortions[0],1.5) + weight*Math.pow(distortions[1],1.5);
	}

}