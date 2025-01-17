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
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import image.SVGMap;
import image.SVGMap.Command;
import image.SVGMap.Path;
import image.SavableImage;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Separator;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import maps.Projection;
import utils.Math2;

/**
 * An application to make vector oblique aspects of map projections
 * 
 * @author Justin Kunimune
 */
public class MapDesignerVector extends MapApplication {

	public static final void main(String[] args) {
		launch(args);
	}
	
	
	
	private static final int DEF_MAX_VTX = 5000;
	private static final int FAST_MAX_VTX = 2000;
	
	private static final FileChooser.ExtensionFilter[] VECTOR_TYPES = {
			new FileChooser.ExtensionFilter("SVG", "*.svg") };
	
	private Region aspectSelector;
	private double[] aspect;
	private SVGMap input;
	private StackPane viewer;
	
	
	
	public MapDesignerVector() {
		super("Map Designer");
	}
	
	
	
	@Override
	public void start(Stage root) {
		super.start(root);
		new Thread(setInputTask(new File("input/Basic.svg"))).start(); //this automatically updates the map
	}
	
	
	@Override
	protected Region makeWidgets() {
		this.aspect = new double[3];
		final Region inputSelector = buildInputSelector(VECTOR_TYPES,
				VECTOR_TYPES[0], this::setInputTask);
		final Region projectionSelector = buildProjectionSelector(this::updateAspect);
		this.aspectSelector = buildAspectSelector(this.aspect, this::updateMap);
		final Region parameterSelector = buildParameterSelector(this::updateMap);
		final Region saveBtn = buildSaveButton(true, "map", VECTOR_TYPES,
				VECTOR_TYPES[0], ()->true, this::calculateTaskForSaving);
		
		final VBox layout = new VBox(V_SPACE,
				inputSelector, new Separator(), projectionSelector,
				new Separator(), aspectSelector, parameterSelector,
				new Separator(), saveBtn);
		
		layout.setAlignment(Pos.CENTER);
		layout.setPrefWidth(GUI_WIDTH);
		
		viewer = new StackPane();
		viewer.setMinWidth(IMG_SIZE);
		viewer.setMinHeight(IMG_SIZE);
		
		final HBox gui = new HBox(MARGIN, layout, viewer);
		gui.setAlignment(Pos.CENTER);
		StackPane.setMargin(gui, new Insets(MARGIN));
		
		return gui;
	}
	
	
	private Task<Void> setInputTask(File file) {
		return new Task<Void>() {
			protected Void call() throws IOException, SAXException, ParserConfigurationException {
				input = new SVGMap(file);
				return null;
			}
			
			protected void failed() {
				if (getException() instanceof IOException)
					showError("File not found!",
							"We couldn't find "+file.getAbsolutePath()+".");
				else if (getException() instanceof SAXException)
					showError("Unreadable file!",
							"We couldn't read "+file.getAbsolutePath()+". It may be corrupt or an unreadable format.");
				else if (getException() instanceof ParserConfigurationException)
					showError("Parser Configuration Error!",
							"My parser configured incorrectly. I blame you.");
				else
					showError("Unexpected error!", getException().getMessage());
			}
			
			protected void succeeded() {
				updateMap();
			}
		};
		
	}
	
	
	private void updateAspect() { //check the visibility of the aspect sliders
		aspectSelector.setVisible(this.getProjection().hasAspect());
		updateMap();
	}
	
	
	private void updateMap() { //execute a new calculation Task immediately
		new Thread(calculateTaskForUpdate()).start();
	}
	
	
	private Task<SavableImage> calculateTaskForUpdate() {
		int maxVtx = this.getParamsChanging() ? FAST_MAX_VTX : DEF_MAX_VTX;
		int step = input.length()/maxVtx+1;
		return calculateTask(step, true);
	}
	
	private Task<SavableImage> calculateTaskForSaving() {
		return calculateTask(0, false);
	}
	
	private Task<SavableImage> calculateTask(int step, boolean render) {
		loadParameters();
		return calculateTask(step, input, getProjection(), aspect.clone(), render ? viewer : null);
	}

	/**
	 * Prepare a task that will load a new map from the given projection and return it as a savable
	 * image, and perhaps render it to a StackPane.
	 * @param step - The number of points to skip on the given input, if you're in a rush.
	 * @param input - The equirectangular input image.
	 * @param proj - The projection to do the mapping.
	 * @param aspect - The oblique axis for the map.
	 * @param viewer - The pane in which to place the newly rendered image, or null if it is not to
	 * 		be rendered.
	 * @return A Task upon which will produce and return the SavableImage when called.
	 */
	public static Task<SavableImage> calculateTask(int step,
												   SVGMap input, Projection proj, double[] aspect, StackPane viewer) {
		return new Task<SavableImage>() {
			private Canvas rendered;

			protected SavableImage call() {
				Iterable<Path> map = MapDesignerVector.calculate(
					  step, input, proj, aspect,
					  this::updateProgress, this::updateMessage);

				if (viewer != null) { //if we are to render,
					updateProgress(-1, 1);
					updateMessage("Rendering map\u2026"); //then render

					int width, height;
					if (proj.isLandscape()) { //fit it to an IMG_SIZE x IMG_SIZE box
						width = IMG_SIZE;
						height = (int)Math.max(IMG_SIZE/proj.getAspectRatio(), 1);
					}
					else {
						width = (int)Math.max(IMG_SIZE/proj.getAspectRatio(), 1);
						height = IMG_SIZE;
					}
					rendered = drawImage(map, proj.getWidth(), proj.getHeight(), width, height);
				}

				return new SavableImage() {
					public void save(File file) throws IOException {
						SVGMap altered = input.replace("Equirectangular", proj.getName());
						altered.save(map, file, -proj.getWidth()/2, proj.getHeight()/2,
									 proj.getWidth(), proj.getHeight()); //save
					}
				};
			}

			protected void failed() {
				getException().printStackTrace();
			}

			protected void succeeded() { //draw the image once successful
				if (viewer != null) {
					viewer.getChildren().clear();
					viewer.getChildren().add(rendered);
				}
			}
		};
	}


	/**
	 * Prepare a task that will load a new map from the given projection and return it as a savable
	 * image, and perhaps render it to a StackPane.
	 * @param step - The number of points to skip on the given input, if you're in a rush.
	 * @param input - The equirectangular input image.
	 * @param proj - The projection to do the mapping.
	 * @param aspect - The oblique axis for the map.
	 * @return A Task upon which will produce and return the SavableImage when called.
	 */
	public static Iterable<Path> calculate(int step,
										   SVGMap input, Projection proj,
										   double[] aspect,
										   BiConsumer<Integer, Integer> updateProgress,
										   Consumer<String> updateMessage) {
		if (updateProgress == null)
			updateProgress = (i, j) -> {};
		if (updateMessage == null)
			updateMessage = (s) -> {};

		updateProgress.accept(-1, 1);
		updateMessage.accept("Generating map\u2026");

		List<Path> theMap = new LinkedList<>();
		int i = 0;
		for (Path pathS: input) {
			updateProgress.accept(i, input.numCurves());
			if (step > 0 && pathS.size() <= step)
				continue; //don't bother drawing singular points unless step is zero
			Path pathP = new Path();
			int j = 0;
			while (j < pathS.size()) {
				Command cmdS = pathS.get(j);
				Command cmdP = new Command(cmdS.type, new double[cmdS.args.length]);
				for (int k = 0; k < cmdS.args.length; k += 2) {
					double[] coords = proj.project(cmdS.args[k+1], cmdS.args[k], aspect);
					if (Double.isNaN(coords[0]) || Double.isNaN(coords[1]))
						System.err.println(proj+" returns "+coords[0]+","+coords[1]+" at "+cmdS.args[k+1]+","+cmdS.args[k]+"!");
					cmdP.args[k] =
						  Math.max(Math.min(coords[0], proj.getWidth()), -proj.getWidth());
					cmdP.args[k+1] =
						  Math.max(Math.min(coords[1], proj.getHeight()), -proj.getHeight());
				}
				pathP.add(cmdP); //TODO: if I was smart, I would divide landmasses that hit an interruption so that I didn't get those annoying lines that cross the map, and then run adaptive resampling to make sure the cuts look clean and not polygonal (e.g. so Antarctica extends all the way to the bottom), but that sounds really hard.

				for (int k = 0; k < Math.max(1, step); k ++) { //increment j by at least 1 and at most step
					if (k != 0 && (j >= pathS.size() - 1 || pathS.get(j).type == 'M'
						  || pathS.get(j).type == 'Z'))
						break; //but pause for every moveto and closepath, and for the last command in the path
					else
						j ++;
				}
			}
			theMap.add(pathP);

			i ++;
		}

		return theMap;
	}


	private static Canvas drawImage(Iterable<Path> paths, double inWidth, double inHeight,
			int outWidth, int outHeight) { //parse the SVG path, with a few modifications
		final double mX = inWidth/2;
		final double mY = inHeight/2;
		Canvas c = new Canvas(outWidth, outHeight);
		GraphicsContext g = c.getGraphicsContext2D();
		g.clearRect(0, 0, c.getWidth(), c.getHeight());
		g.beginPath();
		for (Path path: paths) {
			double startX = 0, startY = 0, lastX = 0, lastY = 0;
			for (Command cmd: path) {
				final double[] args = new double[cmd.args.length];
				for (int i = 0; i < args.length; i ++)
					if (i%2 == 0)
						args[i] = Math2.linInterp(cmd.args[i], -mX, mX, 0, c.getWidth());
					else
						args[i] = Math2.linInterp(cmd.args[i], -mY, mY, c.getHeight(), 0);
				
				switch (cmd.type) {
				case 'M':
					startX = args[0];
					startY = args[1];
					g.moveTo(args[0], args[1]);
					break;
				case 'L':
				case 'T':
					for (int i = 0; i < args.length; i += 2)
						if (Math.hypot(args[i+0]-lastX, args[i+1]-lastY) < outWidth/4.) // break lines that are too long
							g.lineTo(args[i+0], args[i+1]); //TODO: I really need to actually look for interruptions or something
						else
							g.moveTo(args[i+0], args[i+1]);
					break;
				case 'Q':
				case 'S':
					for (int i = 0; i < args.length; i += 4)
						g.quadraticCurveTo(args[i+0], args[i+1], args[i+2], args[i+3]);
					break;
				case 'C':
					for (int i = 0; i < args.length; i += 6)
						g.bezierCurveTo(
								args[i+0], args[i+1], args[i+2], args[i+3], args[i+4], args[i+5]);
					break;
				case 'Z':
					if (Math.hypot(startX-lastX, startY-lastY) < outWidth/4.)
						g.lineTo(startX, startY);
					break;
				default:
					System.err.println("Unsupported movement type: "+cmd.type); //I don't do arcs; they just don't work well with projection
				}
				
				if (args.length > 0) {
					lastX = args[0];
					lastY = args[1];
				}
			}
		}
		g.stroke();
		return c;
	}
}
